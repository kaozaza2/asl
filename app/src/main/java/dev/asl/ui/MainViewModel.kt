package dev.asl.ui

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.asl.core.Asl
import dev.asl.core.Distro
import dev.asl.core.Profile
import dev.asl.core.Progress
import dev.asl.settings.DISTRO_PROFILE_SOURCE
import dev.asl.settings.distroProfile
import dev.asl.settings.appearanceOf
import dev.asl.settings.launchProfile
import dev.asl.ui.terminal.TerminalSurface
import dev.asl.ui.terminal.TerminalTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

const val ACTION_RUN = "dev.asl.action.RUN"
const val SETTINGS_TAB = -1L
const val NO_TAB = -2L

class DistroTask(val label: String, val distroId: String? = null) {
    val progress = MutableStateFlow<Progress?>(null)
    val error = MutableStateFlow<String?>(null)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    /** Tabs live in [TabRegistry] so sessions survive this ViewModel and the process behind them. */
    val tabs: StateFlow<List<TerminalTab>> get() = TabRegistry.tabs
    /** A tab id, [SETTINGS_TAB], or none. */
    val selected = TabRegistry.selected
    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()
    val settingsPage = MutableStateFlow<String?>(null)
    val ctrl = MutableStateFlow(false)
    val alt = MutableStateFlow(false)
    val task = MutableStateFlow<DistroTask?>(null)
    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-shot errors for a snackbar. */
    val messages = _messages.receiveAsFlow()

    init {
        // Windows Terminal's WSL profile generator.
        viewModelScope.launch {
            Asl.distros.distros.collect { distros ->
                val generated = distros.map(::distroProfile)
                val ids = distros.map { it.id }.toSet()
                Asl.settings.update { s ->
                    val known = s.profiles.list.map { it.guid }.toSet()
                    // An empty list may only mean the registry has not loaded yet, so never hide on it.
                    val list = s.profiles.list.map { p ->
                        if (p.source == DISTRO_PROFILE_SOURCE && distros.isNotEmpty() && p.distroId !in ids) p.copy(hidden = true) else p
                    } + generated.filter { it.guid !in known }
                    val fallback = generated.getOrNull(distros.indexOfFirst { it.isDefault }) ?: list.firstOrNull { !it.hidden }
                    val next = s.copy(defaultProfile = s.defaultProfile ?: fallback?.guid, profiles = s.profiles.copy(list = list))
                    // Return the old value when nothing changed, so StateFlow emits and the file is not rewritten.
                    if (next != s) next else s
                }
            }
        }
        // Restore the last run's tabs once the registries are loaded; start() already consumed the state
        // of a process that survived task removal, so restored sessions are only relaunched after a death.
        viewModelScope.launch {
            Asl.distros.distros.collect { distros -> if (distros.isNotEmpty()) TabRegistry.restore() }
        }
    }

    fun profileFor(distro: Distro): Profile {
        val generated = distroProfile(distro)
        return Asl.settings.settings.value.profiles.list.firstOrNull { it.guid == generated.guid } ?: generated
    }

    fun openTab(profile: Profile) = TabRegistry.openTab(profile)

    fun openDefaultTab() {
        Asl.settings.settings.value.launchProfile(Asl.distros.distros.value)?.let(TabRegistry::openTab) ?: openSettings("distros")
    }

    fun closeTab(tab: TerminalTab) {
        TabRegistry.closeTab(tab)
        // The registry doesn't know about the settings tab; fall back to it when it's the only one left.
        if (selected.value == NO_TAB && _settingsOpen.value) selected.value = SETTINGS_TAB
    }

    fun openSettings(page: String? = settingsPage.value) {
        _settingsOpen.value = true
        settingsPage.value = page
        selected.value = SETTINGS_TAB
    }

    fun closeSettings() {
        _settingsOpen.value = false
        if (selected.value == SETTINGS_TAB) selected.value = TabRegistry.tabs.value.lastOrNull()?.id ?: NO_TAB
    }

    val latches = object : TerminalSurface.Latches {
        override fun consumeCtrl() = ctrl.getAndUpdate { false }
        override fun consumeAlt() = alt.getAndUpdate { false }
    }

    fun launch(onError: (String) -> Unit = { _messages.trySend(it) }, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            }
        }
    }

    fun runDistroTask(label: String, block: suspend (onProgress: (Progress) -> Unit) -> Unit) = runDistroTask(null, label, block)

    /** [distroId] marks the settings row the task runs in, so its item is disabled and shows progress. */
    fun runDistroTask(distroId: String?, label: String, block: suspend (onProgress: (Progress) -> Unit) -> Unit) {
        val current = DistroTask(label, distroId).also { task.value = it }
        launch({ current.error.value = it }) {
            block { current.progress.value = it }
            task.compareAndSet(current, null)
        }
    }

    fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_RUN -> {
                val distros = Asl.distros.distros.value
                val name = intent.getStringExtra("distro")
                val distro = name?.let { n -> distros.firstOrNull { it.id == n || it.name.equals(n, ignoreCase = true) } }
                val base = distro?.let(::profileFor)
                    ?: Asl.settings.settings.value.launchProfile(distros)
                    ?: Profile(guid = "{${UUID.randomUUID()}}", name = "ASL")
                openTab(
                    base.copy(
                        distroId = if (name != null) distro?.id ?: name else base.distroId,
                        commandline = intent.getStringExtra("command") ?: base.commandline,
                        startingDirectory = intent.getStringExtra("cwd") ?: base.startingDirectory,
                    ),
                )
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Asl.usb.refresh()
                openSettings("usb")
            }
            else -> if (tabs.value.isEmpty() && !TabRegistry.willRestore && Asl.distros.distros.value.isNotEmpty()) openDefaultTab()
        }
    }
}
