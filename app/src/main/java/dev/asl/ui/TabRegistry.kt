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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Process-wide owner of terminal tabs, so sessions outlive the activity and ViewModel. While the VM
 * foreground service keeps the process alive, the tabs of a task cleared from recents keep running
 * and buffering their output, and reopening the app reattaches to them; they are closed only when
 * they exit themselves or the user closes them. The open tab list is also persisted, so after a
 * real process death reopening relaunches the same profiles instead of a fresh default tab.
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
    private var pendingRestore: List<String>? = null

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
        val guids = prefs.getString(TABS_KEY, null)?.let { raw ->
            runCatching { Json.decodeFromString<List<String>>(raw) }.getOrNull()
        }
        pendingRestore = guids?.takeIf { it.isNotEmpty() }
    }

    /** True while a persisted tab list still waits to be reopened; suppresses the default-tab opening. */
    val willRestore: Boolean get() = pendingRestore != null

    /** Reopens the persisted tabs; [MainViewModel] calls it once distros have loaded. */
    fun restore() {
        val guids = pendingRestore ?: return
        pendingRestore = null
        val profiles = Asl.settings.settings.value.profiles.list
        guids.forEach { guid -> profiles.firstOrNull { it.guid == guid }?.let(::openTab) }
        // The strip position selected in the last run; openTab above left the last restored tab selected.
        selected.value = _tabs.value.getOrNull(prefs.getInt(SELECTED_KEY, 0))?.id ?: NO_TAB
    }

    fun openTab(profile: Profile) {
        val distros = Asl.distros.distros.value
        val distroId = if (profile.distroId != null) {
            distros.firstOrNull { it.id == profile.distroId }?.id
        } else {
            (distros.firstOrNull { it.isDefault } ?: distros.firstOrNull())?.id
        }
        val tab = TerminalTab(nextTabId++, profile, Asl.settings.settings.value.appearanceOf(profile).scrollbackLines)
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
        if (!::prefs.isInitialized) return
        val tabs = _tabs.value
        prefs.edit()
            .putString(TABS_KEY, Json.encodeToString(tabs.map { it.profile.guid }))
            // A selected settings tab maps to position 0; restoring selects the first tab then.
            .putInt(SELECTED_KEY, tabs.indexOfFirst { it.id == selected.value }.coerceAtLeast(0))
            .apply()
    }

    private const val PREFS = "dev.asl.tabs"
    private const val TABS_KEY = "guids"
    private const val SELECTED_KEY = "selected"
}
