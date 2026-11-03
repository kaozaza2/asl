package dev.asl.ui.terminal

import android.content.Context
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


/** One terminal tab: a Termux emulator fed by an agent session in the VM. */
class TerminalTab(val id: Long, val profile: Profile, scrollback: Int) : LogSessionClient {
    private val _title = MutableStateFlow(profile.tabTitle ?: profile.name)
    val title: StateFlow<String> = _title.asStateFlow()
    var view: TerminalSurface? = null

    private var remote: AgentSession? = null
    private var job: Job? = null
    private val size = MutableStateFlow<Pair<Int, Int>?>(null)

    val terminal = TerminalSession(scrollback, this, object : TerminalSession.Host {
        override fun onInput(data: ByteArray, offset: Int, count: Int) {
            remote?.write(data.copyOfRange(offset, offset + count))
        }

        override fun onResize(columns: Int, rows: Int) {
            size.value = columns to rows
            remote?.resize(columns, rows)
        }
    })

    fun start(scope: CoroutineScope, context: Context, request: SessionRequest) {
        job = scope.launch {
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
                val pump = launch { opened.output.collect { terminal.feed(it, 0, it.size) } }
                val code = opened.exitCode.await()
                withTimeoutOrNull(500) { pump.join() }
                pump.cancel()
                terminal.finish(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                end("\u001b[91m${e.message ?: e.toString()}\u001b[0m")
            }
        }
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }

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
        job?.cancel()
        remote?.close()
    }

    private fun print(text: String) {
        val bytes = text.toByteArray()
        terminal.feed(bytes, 0, bytes.size)
    }

    private fun end(message: String) {
        print("\r\n$message")
        terminal.finish(-1)
    }
}
