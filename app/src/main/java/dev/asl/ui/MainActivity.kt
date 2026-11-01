package dev.asl.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import dev.asl.R
import dev.asl.core.Asl
import dev.asl.core.AslSettings
import dev.asl.core.VmState
import dev.asl.databinding.MainActivityBinding
import dev.asl.databinding.MainExtraKeyBinding
import dev.asl.settings.appearanceOf
import dev.asl.settings.scheme
import dev.asl.settings.visibleProfiles
import dev.asl.ui.main.TabAdapter
import dev.asl.ui.main.TabEntry
import dev.asl.ui.main.glyph
import dev.asl.ui.settings.SettingsFragment
import dev.asl.ui.terminal.TerminalSurface
import dev.asl.ui.terminal.TerminalTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class ExtraKey(val label: String, val key: String? = null)

// key: an ExtraKeyCodes name, a latch, "paste", or null to type the label.
private val ExtraKeys = listOf(
    ExtraKey("Esc", "esc"), ExtraKey("Tab", "tab"), ExtraKey("Ctrl", "ctrl"), ExtraKey("Alt", "alt"),
    ExtraKey("←", "left"), ExtraKey("↓", "down"), ExtraKey("↑", "up"), ExtraKey("→", "right"),
    ExtraKey("Home", "home"), ExtraKey("End", "end"), ExtraKey("PgUp", "pgup"), ExtraKey("PgDn", "pgdn"),
    ExtraKey("|"), ExtraKey("/"), ExtraKey("-"), ExtraKey("~"), ExtraKey("Paste", "paste"),
)

private const val SETTINGS_FRAGMENT = "settings"

private fun AslSettings.appearanceOf(tab: TerminalTab) =
    appearanceOf(profiles.list.firstOrNull { it.guid == tab.profile.guid } ?: tab.profile)

class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()
    private lateinit var binding: MainActivityBinding
    private val surfaces = mutableMapOf<TerminalTab, TerminalSurface>()
    private var tone: ToneGenerator? = null
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            moveTaskToBack(true)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBarStyle()
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (savedInstanceState == null) vm.handleIntent(intent)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = cutout.left, right = cutout.right)
            binding.mainTabStrip.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            binding.mainBottomInset.updateLayoutParams {
                height = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars()).bottom
            }
            WindowInsetsCompat.CONSUMED
        }

        val tabs = TabAdapter(
            onSelect = { vm.selected.value = it },
            onClose = { id -> if (id == SETTINGS_TAB) vm.closeSettings() else vm.tabs.value.firstOrNull { it.id == id }?.let(vm::closeTab) },
        )
        binding.mainTabs.adapter = tabs
        (binding.mainTabs.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.mainNewTab.setOnClickListener { vm.openDefaultTab() }
        binding.mainProfiles.setOnClickListener { anchor ->
            val profiles = Asl.settings.settings.value.visibleProfiles(Asl.distros.distros.value)
            PopupMenu(this, anchor).apply {
                menu.setGroupDividerEnabled(true)
                profiles.forEachIndexed { i, p -> menu.add(0, i, i, "${p.glyph}  ${p.name}") }
                menu.add(1, profiles.size, profiles.size, R.string.main_settings)
                setOnMenuItemClickListener { item ->
                    profiles.getOrNull(item.itemId)?.let(vm::openTab) ?: vm.openSettings()
                    true
                }
                show()
            }
        }
        binding.mainEmptyAction.setOnClickListener {
            if (Asl.distros.distros.value.isEmpty()) vm.openSettings("distros") else vm.openDefaultTab()
        }

        val latches = mapOf("ctrl" to vm.ctrl, "alt" to vm.alt)
        ExtraKeys.forEach { k ->
            MainExtraKeyBinding.inflate(layoutInflater, binding.mainExtraKeys, true).root.apply {
                text = k.label
                tag = k.key
                setOnClickListener {
                    val view = vm.tabs.value.firstOrNull { it.id == vm.selected.value }?.view
                    when (k.key) {
                        in latches -> latches.getValue(k.key!!).update { !it }
                        "paste" -> view?.pasteClipboard()
                        null -> view?.typeText(k.label)
                        else -> view?.sendKey(k.key)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.messages.collect { Snackbar.make(binding.mainContent, it, Snackbar.LENGTH_SHORT).show() } }
                launch {
                    Asl.vm.status.collect { binding.mainVmProgress.isVisible = it.state == VmState.Starting || it.state == VmState.Stopping }
                }
                launch {
                    Asl.settings.settings.map { it.launchMode }.distinctUntilChanged().collect { mode ->
                        WindowCompat.getInsetsController(window, window.decorView).run {
                            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            if (mode == "fullscreen") hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                }
                launch {
                    vm.settingsOpen.collect { open ->
                        val existing = supportFragmentManager.findFragmentByTag(SETTINGS_FRAGMENT)
                        when {
                            open && existing == null -> supportFragmentManager.commitNow {
                                add(R.id.main_settings_container, SettingsFragment(), SETTINGS_FRAGMENT)
                            }
                            !open && existing != null -> supportFragmentManager.commitNow { remove(existing) }
                        }
                    }
                }
                latches.forEach { (key, flow) ->
                    launch {
                        flow.collect { latched ->
                            binding.mainExtraKeys.findViewWithTag<TextView>(key).run {
                                setBackgroundColor(if (latched) MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary) else Color.TRANSPARENT)
                                setTextColor(
                                    MaterialColors.getColor(
                                        this,
                                        if (latched) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnSurface,
                                    ),
                                )
                            }
                        }
                    }
                }
                launch {
                    val titled = vm.tabs.flatMapLatest { list ->
                        if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { t -> t.title.map { t to it } }) { it.toList() }
                    }
                    combine(titled, vm.selected, vm.settingsOpen) { titledTabs, selected, settingsOpen ->
                        titledTabs.map { (t, title) -> TabEntry(t.id, title, t.profile.glyph, t.id == selected) } +
                            listOfNotNull(TabEntry(SETTINGS_TAB, getString(R.string.main_settings), null, selected == SETTINGS_TAB).takeIf { settingsOpen })
                    }.collect { entries ->
                        tabs.submitList(entries) {
                            entries.indexOfFirst { it.selected }.takeIf { it >= 0 }?.let(binding.mainTabs::smoothScrollToPosition)
                        }
                    }
                }
                launch {
                    combine(vm.tabs, vm.selected, Asl.settings.settings, Asl.distros.distros) { _, _, _, _ -> }.collect { applyTabState() }
                }
            }
        }
    }

    private fun applyTabState() {
        val tabList = vm.tabs.value
        val selected = vm.selected.value
        val settings = Asl.settings.settings.value
        val distros = Asl.distros.distros.value
        (surfaces.keys - tabList.toSet()).forEach { tab ->
            val surface = surfaces.remove(tab)!!
            binding.mainTerminals.removeView(surface.parent as View)
            if (tab.view === surface) tab.view = null
        }
        tabList.forEach { tab ->
            val surface = surfaces.getOrPut(tab) {
                TerminalSurface(
                    this@MainActivity,
                    tab,
                    vm.latches,
                    onExitedEnter = { vm.closeTab(tab) },
                    onBell = {
                        val bellStyle = Asl.settings.settings.value.appearanceOf(tab).bellStyle
                        if (bellStyle == "audible" || bellStyle == "all") tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                        if (bellStyle == "visual" || bellStyle == "all") binding.root.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    },
                ).also { binding.mainTerminals.addView(FrameLayout(this@MainActivity).apply { addView(it) }) }
            }
            val appearance = settings.appearanceOf(tab)
            val scheme = settings.scheme(appearance.colorScheme)
            val padding = (appearance.padding * resources.displayMetrics.density).toInt()
            (surface.parent as View).apply {
                setBackgroundColor(ColorUtils.setAlphaComponent(Color.parseColor(scheme.background), appearance.opacity * 255 / 100))
                setPadding(padding, padding, padding, padding)
                visibility = if (tab.id == selected) View.VISIBLE else View.INVISIBLE
            }
            surface.configure(appearance, scheme)
            surface.show(tab.id == selected)
        }

        val terminalSelected = tabList.any { it.id == selected }
        binding.mainSettingsContainer.isVisible = selected == SETTINGS_TAB
        binding.mainExtraKeysScroll.isVisible = terminalSelected
        binding.mainEmpty.isVisible = selected != SETTINGS_TAB && !terminalSelected
        binding.mainEmptyTitle.setText(if (distros.isEmpty()) R.string.main_no_distros else R.string.main_no_tabs)
        binding.mainEmptyBody.setText(if (distros.isEmpty()) R.string.main_no_distros_body else R.string.main_no_tabs_body)
        binding.mainEmptyAction.setText(if (distros.isEmpty()) R.string.main_install_distro else R.string.main_open_tab)

        // Re-adding keeps this above callbacks the settings fragment registered later.
        back.isEnabled = selected != SETTINGS_TAB
        back.remove()
        onBackPressedDispatcher.addCallback(this@MainActivity, back)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarStyle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onDestroy() {
        surfaces.forEach { (tab, surface) -> if (tab.view === surface) tab.view = null }
        tone?.release()
        super.onDestroy()
    }

    private fun applySystemBarStyle() {
        val style = if (resources.configuration.isNightModeActive) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(style, style)
    }
}
