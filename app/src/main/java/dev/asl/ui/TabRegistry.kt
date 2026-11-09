package dev.asl.ui

import android.content.Context
import android.content.SharedPreferences
import dev.asl.core.Asl
import dev.asl.core.Profile
import dev.asl.core.SessionRequest
import dev.asl.settings.appearanceOf
import dev.asl.ui.terminal.TerminalTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Process-wide owner of terminal tabs, so sessions outlive the activity and ViewModel. While the VM
 * foreground service keeps the process alive, the tabs of a task cleared from recents keep running
 * and buffering their output, and reopening the app reattaches to them; they are closed only when
 * they exit themselves or the user closes them. The open tab list is also persisted, so after a
 * real process death reopening relaunches the same profiles instead of a fresh default tab, with the
 * output each tab showed replayed above its new session (as text: colors and running programs are gone).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TabRegistry {
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()
    /** A tab id, [SETTINGS_TAB], or [NO_TAB]. */
    val selected = MutableStateFlow(NO_TAB)

    private val _runningSessions = MutableStateFlow(0)
    /** Tabs whose session is live, including ones still waiting on their VM, for the service notification. */
    val runningSessions: StateFlow<Int> = _runningSessions.asStateFlow()

    private var nextTabId = 0L
    private var pendingRestore: List<SavedTab>? = null

    /** A persisted tab: its profile, and the key naming its saved output in [transcripts]. */
    @Serializable
    private data class SavedTab(val guid: String, val key: String = "")

    private lateinit var transcripts: File
    private val transcriptWrites = Mutex()
    private var snapshotPosted = false
    private var restoring = false

    init {
        scope.launch {
            _tabs.flatMapLatest { list ->
                if (list.isEmpty()) flowOf(0) else combine(list.map { it.running }) { runs -> runs.count { it } }
            }.collect { _runningSessions.value = it }
        }
        // Persisting the open list and selection covers both graceful changes and a sudden process death.
        scope.launch { _tabs.collect { save() } }
        scope.launch { selected.collect { save() } }
    }

    /** Reads the last run's persisted tab list; called once per process from AslApp. */
    fun start(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        transcripts = File(context.filesDir, "transcripts")
        val raw = prefs.getString(TABS_KEY, null)
        // Earlier builds stored bare profile guids, with no saved output.
        val saved = raw?.let {
            runCatching { Json.decodeFromString<List<SavedTab>>(it) }.getOrNull()
                ?: runCatching { Json.decodeFromString<List<String>>(it).map(::SavedTab) }.getOrNull()
        }
        pendingRestore = saved?.takeIf { it.isNotEmpty() }
    }

    /** True while a persisted tab list still waits to be reopened; suppresses the default-tab opening. */
    val willRestore: Boolean get() = pendingRestore != null

    /** Reopens the persisted tabs; [MainViewModel] calls it once distros have loaded. */
    fun restore() {
        val saved = pendingRestore ?: return
        if (restoring) return
        restoring = true
        scope.launch {
            val outputs = withContext(Dispatchers.IO) {
                saved.map { tab -> tab.key.takeIf { it.isNotEmpty() }?.let { runCatching { transcriptFile(it).readText() }.getOrNull() } }
            }
            val profiles = Asl.settings.settings.value.profiles.list
            // pendingRestore stays set while the tabs reopen, so save() can't persist a partial list and
            // delete the output files of tabs not reopened yet.
            saved.forEachIndexed { i, tab ->
                val profile = profiles.firstOrNull { it.guid == tab.guid } ?: return@forEachIndexed
                // Keeping the key keeps the saved output on disk until this tab's first snapshot replaces it.
                openTab(profile, tab.key.ifEmpty { null }, outputs[i])
            }
            // The strip position selected in the last run; openTab above left the last restored tab selected.
            selected.value = _tabs.value.getOrNull(prefs.getInt(SELECTED_KEY, 0))?.id ?: NO_TAB
            pendingRestore = null
            restoring = false
            save()
        }
    }

    fun openTab(profile: Profile) = openTab(profile, null, null)

    private fun openTab(profile: Profile, key: String?, restoredOutput: String?) {
        val distros = Asl.distros.distros.value
        val distroId = if (profile.distroId != null) {
            distros.firstOrNull { it.id == profile.distroId }?.id
        } else {
            (distros.firstOrNull { it.isDefault } ?: distros.firstOrNull())?.id
        }
        val scrollback = Asl.settings.settings.value.appearanceOf(profile).scrollbackLines
        val tab = if (key != null) {
            TerminalTab(nextTabId++, profile, scrollback, key, restoredOutput, ::outputChanged)
        } else {
            TerminalTab(nextTabId++, profile, scrollback, onOutput = ::outputChanged)
        }
        _tabs.update { it + tab }
        selected.value = tab.id
        tab.start(
            scope,
            appContext,
            SessionRequest(
                distroId = distroId,
                user = profile.user?.takeIf { it.isNotBlank() },
                cwd = profile.startingDirectory?.takeIf { it.isNotBlank() },
                argv = listOfNotNull(profile.commandline?.takeIf { it.isNotBlank() }),
            ),
        )
    }

    fun closeTab(tab: TerminalTab) {
        val index = _tabs.value.indexOf(tab)
        if (index < 0) return
        val remaining = _tabs.value - tab
        _tabs.value = remaining
        tab.close()
        if (selected.value == tab.id) {
            selected.value = remaining.getOrNull(index.coerceAtMost(remaining.size - 1))?.id ?: NO_TAB
        }
    }

    private fun save() {
        // Until restore() runs, the open list is only what this process opened since start; writing it
        // would overwrite the persisted tabs that a process death right now would need.
        if (!::prefs.isInitialized || pendingRestore != null) return
        val tabs = _tabs.value
        prefs.edit()
            .putString(TABS_KEY, Json.encodeToString(tabs.map { SavedTab(it.profile.guid, it.key) }))
            // A selected settings tab maps to position 0; restoring selects the first tab then.
            .putInt(SELECTED_KEY, tabs.indexOfFirst { it.id == selected.value }.coerceAtLeast(0))
            .apply()
        // Drop the saved output of tabs that are no longer open.
        val keep = tabs.map { "${it.key}.txt" }.toSet()
        scope.launch(Dispatchers.IO) {
            transcriptWrites.withLock { transcripts.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() } }
        }
    }

    /** Called on every screen change; snapshots the output at most once per [SNAPSHOT_DELAY_MS]. */
    private fun outputChanged() {
        if (snapshotPosted) return
        snapshotPosted = true
        scope.launch {
            delay(SNAPSHOT_DELAY_MS)
            snapshotPosted = false
            saveOutput()
        }
    }

    /**
     * Writes the output of tabs that changed since their last snapshot, so a process death (an app update,
     * a force stop, low memory) loses at most a few seconds of it. The activity also calls this when it stops.
     */
    fun saveOutput() {
        if (!::transcripts.isInitialized || pendingRestore != null) return
        // The emulator is only safe to read on the main thread; the files are written off it.
        val changed = _tabs.value.filter { it.outputDirty }.mapNotNull { tab ->
            tab.outputDirty = false
            tab.transcript()?.let { tab.key to it.takeLast(MAX_SAVED_CHARS) }
        }
        if (changed.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            transcriptWrites.withLock {
                transcripts.mkdirs()
                changed.forEach { (key, text) ->
                    val file = transcriptFile(key)
                    // Only open tabs keep output; a tab closed while this snapshot was pending is skipped.
                    if (_tabs.value.none { it.key == key }) return@forEach
                    val tmp = File(transcripts, "$key.tmp")
                    runCatching {
                        tmp.writeText(text)
                        if (!tmp.renameTo(file)) tmp.delete()
                    }
                }
            }
        }
    }

    private fun transcriptFile(key: String) = File(transcripts, "$key.txt")

    private const val SNAPSHOT_DELAY_MS = 3_000L
    /** About a full default scrollback (9001 lines) of typical shell output. */
    private const val MAX_SAVED_CHARS = 1_000_000
    private const val PREFS = "dev.asl.tabs"
    private const val TABS_KEY = "guids"
    private const val SELECTED_KEY = "selected"
}
