package dev.asl.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import dev.asl.R
import dev.asl.core.Asl
import dev.asl.core.AslSettings
import dev.asl.core.Distro
import dev.asl.core.MIN_FORWARDED_PORT
import dev.asl.core.Profile
import dev.asl.core.ProfileAppearance
import dev.asl.core.VmState
import dev.asl.databinding.SettingsPrefConsoleBinding
import dev.asl.databinding.SettingsPrefProfileActionsBinding
import dev.asl.databinding.SettingsPrefVmStatusBinding
import dev.asl.settings.DISTRO_PROFILE_SOURCE
import dev.asl.settings.appearanceOf
import dev.asl.settings.scheme
import dev.asl.settings.visibleProfiles
import dev.asl.ui.terminal.BundledFontNames
import dev.asl.ui.terminal.VmPermissions
import dev.asl.ui.terminal.importFont
import dev.asl.ui.terminal.importedFonts
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StartupFragment : StorePreferenceFragment() {
    override val xml = intArrayOf(R.xml.pref_startup)

    override fun fields() = mapOf(
        "defaultProfile" to Field({ it.defaultProfile }, { s, v -> s.copy(defaultProfile = v as String?) }),
        "launchMode" to Field({ it.launchMode }, { s, v -> s.copy(launchMode = v as String) }),
    )

    override fun bind(s: AslSettings, distros: List<Distro>) {
        val profiles = s.visibleProfiles(distros)
        findPreference<ListPreference>("defaultProfile")?.apply {
            entries = profiles.map { it.name }.toTypedArray()
            entryValues = profiles.map { it.guid }.toTypedArray()
        }
    }
}

class AppearanceFragment : StorePreferenceFragment() {
    override val xml = intArrayOf(R.xml.pref_app_appearance)
    override fun fields() = mapOf("theme" to Field({ it.theme }, { s, v -> s.copy(theme = v as String) }))
}

/** Profiles › Defaults when the argument is empty, otherwise the profile with that guid. */
class ProfileFragment : StorePreferenceFragment() {
    private val guid by lazy { requireArguments().getString(ARG).orEmpty() }
    private var missing = false
    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val app = context?.applicationContext ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        vm.launch {
            val name = withContext(Dispatchers.IO) { app.importFont(uri) }
            Asl.settings.update { table.getValue("fontFace").set(it, name) }
        }
    }
    private val preview by lazy {
        ViewPreference(requireContext(), R.layout.settings_pref_swatch) { view ->
            val s = Asl.settings.settings.value
            (view as SchemeSwatchView).scheme = s.scheme(table.getValue("colorScheme").get(s).toString())
        }
    }
    private val actions by lazy {
        ViewPreference(requireContext(), R.layout.settings_pref_profile_actions) bind@{ view ->
            val b = SettingsPrefProfileActionsBinding.bind(view)
            val profile = profile(Asl.settings.settings.value) ?: return@bind
            b.open.setOnClickListener { vm.openTab(profile) }
            b.duplicate.setOnClickListener {
                val copy = profile.copy(guid = "{${UUID.randomUUID()}}", name = getString(R.string.settings_copy_name, profile.name), source = null)
                Asl.settings.update { it.copy(profiles = it.profiles.copy(list = it.profiles.list + copy)) }
                vm.settingsPage.value = "profile:${copy.guid}"
            }
            b.delete.isVisible = profile.source != DISTRO_PROFILE_SOURCE || Asl.distros.distros.value.none { it.id == profile.distroId }
            b.delete.setOnClickListener {
                confirmDialog(getString(R.string.settings_delete_profile_title), getString(R.string.settings_delete_profile_message, profile.name), getString(R.string.settings_delete)) {
                    Asl.settings.update { s ->
                        s.copy(
                            defaultProfile = s.defaultProfile.takeIf { it != guid },
                            profiles = s.profiles.copy(list = s.profiles.list.filter { it.guid != guid }),
                        )
                    }
                    vm.settingsPage.value = null
                }
            }
        }
    }

    override val xml get() = if (guid.isEmpty()) intArrayOf(R.xml.pref_profile_appearance) else intArrayOf(R.xml.pref_profile, R.xml.pref_profile_appearance)

    private fun profile(s: AslSettings) = s.profiles.list.firstOrNull { it.guid == guid }

    private fun edit(s: AslSettings, transform: (Profile) -> Profile) =
        s.copy(profiles = s.profiles.copy(list = s.profiles.list.map { if (it.guid == guid) transform(it) else it }))

    override fun fields(): Map<String, Field> {
        val get = { s: AslSettings -> profile(s)?.let { s.appearanceOf(it) } ?: s.profiles.defaults }
        // A profile that doesn't override its appearance is never written to; its editor is hidden.
        val set = { s: AslSettings, t: (ProfileAppearance) -> ProfileAppearance ->
            if (guid.isEmpty()) s.copy(profiles = s.profiles.copy(defaults = t(s.profiles.defaults))) else edit(s) { it.copy(appearance = it.appearance?.let(t)) }
        }
        val appearance = mapOf(
            "colorScheme" to Field({ get(it).colorScheme }, { s, v -> set(s) { it.copy(colorScheme = v as String) } }),
            "fontFace" to Field({ get(it).fontFace }, { s, v -> set(s) { a -> a.copy(fontFace = (v as String?)?.ifBlank { null } ?: a.fontFace) } }),
            "fontSize" to intField(4..48, " pt", { get(it).fontSize }) { s, v -> set(s) { it.copy(fontSize = v) } },
            "cursorShape" to Field({ get(it).cursorShape }, { s, v -> set(s) { it.copy(cursorShape = v as String) } }),
            "opacity" to intField(0..100, "%", { get(it).opacity }) { s, v -> set(s) { it.copy(opacity = v) } },
            "padding" to intField(0..64, " px", { get(it).padding }) { s, v -> set(s) { it.copy(padding = v) } },
            "scrollbackLines" to intField(0..100_000, "", { get(it).scrollbackLines }) { s, v -> set(s) { it.copy(scrollbackLines = v) } },
            "bellStyle" to Field({ get(it).bellStyle }, { s, v -> set(s) { it.copy(bellStyle = v as String) } }),
        )
        if (guid.isEmpty()) return appearance
        return appearance + mapOf(
            "name" to Field({ profile(it)?.name }, { s, v -> edit(s) { p -> p.copy(name = (v as String?)?.ifBlank { null } ?: p.name) } }),
            "icon" to textField(get = { profile(it)?.icon }) { s, v -> edit(s) { it.copy(icon = v) } },
            "distroId" to Field({ profile(it)?.distroId.orEmpty() }, { s, v -> edit(s) { it.copy(distroId = (v as String?)?.ifEmpty { null }) } }),
            "commandline" to textField(getString(R.string.settings_login_shell), true, { profile(it)?.commandline }) { s, v -> edit(s) { it.copy(commandline = v) } },
            "startingDirectory" to textField("~", true, { profile(it)?.startingDirectory }) { s, v -> edit(s) { it.copy(startingDirectory = v) } },
            "user" to textField(get = { profile(it)?.user }) { s, v -> edit(s) { it.copy(user = v) } },
            "tabTitle" to textField(get = { profile(it)?.tabTitle }) { s, v -> edit(s) { it.copy(tabTitle = v) } },
            "hidden" to Field({ profile(it)?.hidden }, { s, v -> edit(s) { it.copy(hidden = v as Boolean) } }),
            "gui" to Field({ profile(it)?.gui == true }, { s, v -> edit(s) { it.copy(gui = v as Boolean) } }),
            "overrideAppearance" to Field({ profile(it)?.appearance != null }, { s, v ->
                edit(s) { it.copy(appearance = if (v as Boolean) it.appearance ?: s.profiles.defaults else null) }
            }),
        )
    }

    override fun onPreferencesCreated() {
        findPreference<ListPreference>("fontFace")?.setOnPreferenceChangeListener { _, value ->
            (value != CHOOSE_FONT).also { keep ->
                if (!keep) pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
            }
        }
        findPreference<PreferenceCategory>("text")?.addPreference(preview.apply { order = -1 })
        if (guid.isEmpty()) {
            preferenceScreen.addPreference(
                Preference(requireContext()).apply {
                    order = -1
                    summary = getString(R.string.settings_defaults_summary)
                    isSelectable = false
                    isIconSpaceReserved = false
                },
            )
        } else {
            preferenceScreen.addPreference(actions.apply { order = -1 })
        }
    }

    override fun bind(s: AslSettings, distros: List<Distro>) {
        findPreference<ListPreference>("colorScheme")?.apply {
            entries = s.schemes.map { it.name }.toTypedArray()
            entryValues = entries
        }
        findPreference<ListPreference>("fontFace")?.apply {
            val current = table.getValue("fontFace").get(s) as String
            val fonts = (BundledFontNames + requireContext().importedFonts().keys).let { if (current in it) it else it + current }
            entries = (fonts + getString(R.string.settings_font_choose)).toTypedArray()
            entryValues = (fonts + CHOOSE_FONT).toTypedArray()
        }
        preview.refresh()
        if (guid.isEmpty() || missing) return
        val profile = profile(s) ?: run {
            missing = true
            preferenceScreen.removeAll()
            preferenceScreen.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.settings_profile_missing)
                    isSelectable = false
                    isIconSpaceReserved = false
                },
            )
            return
        }
        val generated = profile.source == DISTRO_PROFILE_SOURCE
        findPreference<ListPreference>("distroId")?.apply {
            entries = (listOf(getString(R.string.settings_default_distro)) + distros.map { it.name }).toTypedArray()
            entryValues = (listOf("") + distros.map { it.id }).toTypedArray()
            isEnabled = !generated
        }
        descriptions["distroId"] = getString(R.string.settings_generated_profile).takeIf { generated }
        table.getValue("user").hint = distros.firstOrNull { it.id == profile.distroId }?.defaultUser ?: getString(R.string.settings_default_user)
        listOf("text", "window", "advanced").forEach { findPreference<Preference>(it)?.isVisible = profile.appearance != null }
        actions.refresh()
    }
}

class NetworkFragment : StorePreferenceFragment() {
    override val xml = intArrayOf(R.xml.pref_network)

    override fun fields() = mapOf(
        "localhostForwarding" to Field({ it.asl.localhostForwarding }, { s, v -> s.copy(asl = s.asl.copy(localhostForwarding = v as Boolean)) }),
        "bindAllInterfaces" to Field({ it.asl.bindAllInterfaces }, { s, v -> s.copy(asl = s.asl.copy(bindAllInterfaces = v as Boolean)) }),
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val category = findPreference<PreferenceCategory>("ports") ?: return
        collectWhileStarted(Asl.vm.forwardedPorts) { ports ->
            category.removeAll()
            val rows = ports.map { p ->
                getString(R.string.settings_port_title, if (p.bindAddress == "0.0.0.0") "0.0.0.0" else "localhost", p.port) to
                    resources.getQuantityString(R.plurals.settings_active_connections, p.active, p.active)
            }.ifEmpty { listOf(null to getString(R.string.settings_ports_empty)) } + (null to getString(R.string.settings_ports_note, MIN_FORWARDED_PORT))
            rows.forEach { (title, summary) ->
                category.addPreference(
                    Preference(requireContext()).apply {
                        this.title = title
                        this.summary = summary
                        isSelectable = false
                        isPersistent = false
                        isIconSpaceReserved = false
                    },
                )
            }
        }
    }
}

/** The .wslconfig equivalent plus VM controls and the console log. */
class VmFragment : StorePreferenceFragment() {
    override val xml = intArrayOf(R.xml.pref_vm)
    private val status by lazy {
        ViewPreference(requireContext(), R.layout.settings_pref_vm_status) { view ->
            val b = SettingsPrefVmStatusBinding.bind(view)
            val st = Asl.vm.status.value
            b.status.text = listOfNotNull(st.state.name, st.message).joinToString(" · ")
            b.start.isEnabled = st.state == VmState.Stopped || st.state == VmState.Error
            b.shutdown.isEnabled = st.state == VmState.Running
            b.restart.isEnabled = st.state == VmState.Running
            b.start.setOnClickListener { vm.launch { Asl.vm.ensureRunning() } }
            b.shutdown.setOnClickListener { vm.launch { Asl.vm.shutdown() } }
            b.restart.setOnClickListener { vm.launch { Asl.vm.restart() } }
        }
    }
    private val console by lazy {
        ViewPreference(requireContext(), R.layout.settings_pref_console) { view ->
            val b = SettingsPrefConsoleBinding.bind(view)
            b.log.text = Asl.vm.consoleLog.value.ifEmpty { getString(R.string.settings_console_empty) }
            b.scroll.post { b.scroll.scrollTo(0, b.log.bottom) }
        }
    }

    override fun fields() = mapOf(
        "memoryMb" to intField(512..65_536, " MB", { it.asl.memoryMb }) { s, v -> s.copy(asl = s.asl.copy(memoryMb = v)) },
        "processors" to Field({ if (it.asl.matchHostCpus) "host" else "one" }, { s, v -> s.copy(asl = s.asl.copy(matchHostCpus = v == "host")) }),
        "defaultDiskSizeGb" to intField(1..2048, " GB", { it.asl.defaultDiskSizeGb }) { s, v -> s.copy(asl = s.asl.copy(defaultDiskSizeGb = v)) },
        "kernelCommandLine" to Field({ it.asl.kernelCommandLine }, { s, v -> s.copy(asl = s.asl.copy(kernelCommandLine = v as String? ?: "")) }, monospace = true),
        "autoStart" to Field({ it.asl.autoStart }, { s, v -> s.copy(asl = s.asl.copy(autoStart = v as Boolean)) }),
        "mountAndroidStorage" to Field({ it.asl.mountAndroidStorage }, { s, v -> s.copy(asl = s.asl.copy(mountAndroidStorage = v as Boolean)) }),
        "androidStoragePath" to Field(
            { it.asl.androidStoragePath },
            { s, v -> s.copy(asl = s.asl.copy(androidStoragePath = (v as String?)?.ifBlank { null } ?: s.asl.androidStoragePath)) },
            monospace = true,
        ),
    )

    override fun onPreferencesCreated() {
        val context = requireContext()
        val category = findPreference<PreferenceCategory>("status") ?: return
        category.addPreference(status)
        val missing = VmPermissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            val commands = missing.joinToString("\n") { "adb shell pm grant ${context.packageName} $it" }
            category.addPreference(
                Preference(context).apply {
                    title = getString(R.string.settings_permission_required)
                    summary = getString(R.string.settings_permission_summary, commands)
                    isPersistent = false
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", commands))
                        Toast.makeText(context, R.string.settings_copied, Toast.LENGTH_SHORT).show()
                        true
                    }
                },
            )
        }
        findPreference<Preference>("allFiles")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)))
            true
        }
        findPreference<PreferenceCategory>("console")?.addPreference(console)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectWhileStarted(Asl.vm.status) { status.refresh() }
        collectWhileStarted(Asl.vm.consoleLog) { console.refresh() }
    }

    override fun onResume() {
        super.onResume()
        findPreference<Preference>("allFiles")?.summary =
            getString(if (Environment.isExternalStorageManager()) R.string.settings_all_files_granted else R.string.settings_all_files_needed)
    }
}

private const val CHOOSE_FONT = "asl:choose-font"
