package dev.asl.ui.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession
import dev.asl.R
import dev.asl.core.Asl
import dev.asl.core.Profile
import dev.asl.core.SessionRequest
import dev.asl.core.TerminalSession as AgentSession
import dev.asl.core.VmState
import dev.asl.vm.missingVmPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull


/** One terminal tab: a Termux emulator fed by an agent session in the VM. */
/**
 * One terminal tab. [key] names its saved output on disk; [restoredOutput] is the text a tab of the previous
 * process showed, replayed above the new session; [onOutput] runs on the main thread when the screen changes.
 */
class TerminalTab(
    val id: Long,
    val profile: Profile,
    scrollback: Int,
    val key: String = java.util.UUID.randomUUID().toString(),
    private val restoredOutput: String? = null,
    private val onOutput: () -> Unit = {},
) : LogSessionClient {
    /** Set when the screen changed since the last [transcript] snapshot; main thread only. */
    var outputDirty = false
    private val _title = MutableStateFlow(profile.tabTitle ?: profile.name)
    val title: StateFlow<String> = _title.asStateFlow()
    var view: TerminalSurface? = null

    private var remote: AgentSession? = null
    private var job: Job? = null
    private val size = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _running = MutableStateFlow(false)
    /** True from the start attempt until the remote session exits or the tab closes. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // Keyboard text arrives one code point per onInput (TerminalView.sendTextToTerminal), so a long paste
    // through the IME would be one frame per character. Input is gathered here and sent once per
    // main-loop pass instead, split into frames of at most MAX_INPUT_FRAME bytes.
    private val pendingInput = java.io.ByteArrayOutputStream()
    private var flushPosted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val flushInput = Runnable {
        val bytes = synchronized(pendingInput) {
            flushPosted = false
            pendingInput.toByteArray().also { pendingInput.reset() }
        }
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + MAX_INPUT_FRAME)
            remote?.write(bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    val terminal = TerminalSession(scrollback, this, object : TerminalSession.Host {
        override fun onInput(data: ByteArray, offset: Int, count: Int) {
            synchronized(pendingInput) {
                pendingInput.write(data, offset, count)
                if (flushPosted) return
                flushPosted = true
            }
            mainHandler.post(flushInput)
        }

        override fun onResize(columns: Int, rows: Int) {
            size.value = columns to rows
            remote?.resize(columns, rows)
        }
    })

    fun start(scope: CoroutineScope, context: Context, request: SessionRequest) {
        job = scope.launch {
            _running.value = true
            restoredOutput?.takeIf { it.isNotBlank() }?.let { text ->
                print(text.replace("\n", "\r\n") + "\r\n\u001b[2m[output from the previous session]\u001b[0m\r\n")
            }
            val missing = context.missingVmPermissions()
            if (missing.isNotEmpty()) {
                return@launch end(
                    "\u001b[91m${context.getString(R.string.main_vm_permission)}\u001b[0m\r\n${context.getString(R.string.main_vm_adb_command)}\r\n\r\n" +
                        missing.joinToString("") { "  adb shell pm grant ${context.packageName} $it\r\n" },
                )
            }
            if (request.distroId == null) return@launch end("\u001b[91m${context.getString(R.string.main_no_distro_name)}\u001b[0m")
            val booting = Asl.vm.status.value.state != VmState.Running
            if (booting) print(context.getString(R.string.main_vm_starting))
            try {
                Asl.vm.ensureRunning()
                val (cols, rows) = size.filterNotNull().first()
                val opened = Asl.vm.openSession(request.copy(cols = cols, rows = rows))
                remote = opened
                if (booting) print("\r\u001b[2K")
                // feed() blocks while the screen's queue is full and the main thread is what drains it, so the
                // pump must never run there; blocking here is the backpressure that slows a flood down.
                val pump = launch(Dispatchers.IO) { opened.output.collect { terminal.feed(it, 0, it.size) } }
                val code = opened.exitCode.await()
                // The output channel closes after the exit frame, so this only waits for the tail to drain.
                withTimeoutOrNull(5_000) { pump.join() }
                pump.cancel()
                terminal.finish(code)
                _running.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                end("\u001b[91m${e.message ?: e.toString()}\u001b[0m")
            }
        }
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
        outputDirty = true
        onOutput()
    }

    /** The normal screen's scrollback and screen as text, wrapped lines joined; main thread only. */
    fun transcript(): String? = terminal.emulator?.mainBuffer?.transcriptText

    override fun onTitleChanged(changedSession: TerminalSession) {
        if (profile.tabTitle == null) terminal.title?.takeIf { it.isNotBlank() }?.let { _title.value = it }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        view?.copy(text ?: return)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        view?.pasteClipboard()
    }

    override fun onBell(session: TerminalSession) {
        view?.onBell?.invoke()
    }

    override fun onColorsChanged(session: TerminalSession) {
        view?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int? = view?.cursorStyle

    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    fun close() {
        _running.value = false
        mainHandler.removeCallbacks(flushInput)
        job?.cancel()
        remote?.close()
    }

    private suspend fun print(text: String) {
        val bytes = text.toByteArray()
        withContext(Dispatchers.IO) { terminal.feed(bytes, 0, bytes.size) }
    }

    private suspend fun end(message: String) {
        print("\r\n$message")
        terminal.finish(-1)
        _running.value = false
    }

    private companion object {
        const val MAX_INPUT_FRAME = 32 * 1024
    }
}
