package dev.asl.ui.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import dev.asl.R
import dev.asl.core.Asl
import dev.asl.core.Profile
import dev.asl.databinding.ItemSettingsNavBinding
import dev.asl.databinding.SettingsFragmentBinding
import dev.asl.ui.MainViewModel
import dev.asl.ui.SETTINGS_TAB
import dev.asl.ui.main.glyph
import kotlinx.coroutines.flow.combine
import java.util.UUID

private enum class NavGroup(val title: Int?) { General(null), Profiles(R.string.settings_group_profiles), Linux(R.string.settings_group_linux), Footer(null) }

private class NavEntry(val key: String, val label: Int, val icon: Int, val group: NavGroup, val page: () -> Fragment)

private val NavEntries = listOf(
    NavEntry("startup", R.string.settings_startup, R.drawable.ic_settings_startup, NavGroup.General, ::StartupFragment),
    NavEntry("appearance", R.string.settings_appearance, R.drawable.ic_settings_appearance, NavGroup.General, ::AppearanceFragment),
    NavEntry("schemes", R.string.settings_schemes, R.drawable.ic_settings_schemes, NavGroup.General, ::SchemesFragment),
    NavEntry("defaults", R.string.settings_defaults, R.drawable.ic_settings_defaults, NavGroup.Profiles, ::ProfileFragment),
    NavEntry("distros", R.string.settings_distros, R.drawable.ic_settings_distros, NavGroup.Linux, ::DistrosFragment),
    NavEntry("network", R.string.settings_network, R.drawable.ic_settings_network, NavGroup.Linux, ::NetworkFragment),
    NavEntry("usb", R.string.settings_usb, R.drawable.ic_settings_usb, NavGroup.Linux, ::UsbFragment),
    NavEntry("vm", R.string.settings_vm, R.drawable.ic_settings_vm, NavGroup.Linux, ::VmFragment),
    NavEntry("json", R.string.settings_json, R.drawable.ic_settings_json, NavGroup.Footer, ::JsonFragment),
)

private val Pages: Map<String, () -> Fragment> =
    NavEntries.associate { it.key to it.page } + mapOf("profile" to ::ProfileFragment, "scheme" to ::SchemeFragment)

private enum class RowKind { Header, Divider, Item }

private data class NavRow(val key: String, val kind: RowKind, val label: String = "", val icon: Int = 0, val glyph: String? = null, val selected: Boolean = false)

private const val ADD_PROFILE = "add-profile"
private const val WIDE_PANE_DP = 720

/** Windows Terminal's settings layout: a navigation pane beside the page, or list then page on narrow screens. */
class SettingsFragment : Fragment(R.layout.settings_fragment) {
    private val vm by activityViewModels<MainViewModel>()
    private var back: OnBackPressedCallback? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = SettingsFragmentBinding.bind(view)
        val density = resources.displayMetrics.density
        var wide = resources.configuration.screenWidthDp >= WIDE_PANE_DP
        val nav = ItemAdapter(ItemSettingsNavBinding::inflate, NavRow::key) { row ->
            header.isVisible = row.kind == RowKind.Header
            header.text = row.label
            divider.isVisible = row.kind == RowKind.Divider
            item.isVisible = row.kind == RowKind.Item
            item.isSelected = row.selected
            indicator.isInvisible = !row.selected
            icon.isVisible = row.icon != 0
            if (row.icon != 0) icon.setImageResource(row.icon)
            glyph.isVisible = row.glyph != null
            glyph.text = row.glyph
            label.text = row.label
            item.setOnClickListener {
                vm.settingsPage.value = if (row.key != ADD_PROFILE) row.key else {
                    val profile = Profile(guid = "{${UUID.randomUUID()}}", name = getString(R.string.settings_new_profile))
                    Asl.settings.update { it.copy(profiles = it.profiles.copy(list = it.profiles.list + profile)) }
                    "profile:${profile.guid}"
                }
            }
        }
        b.nav.itemAnimator = null
        b.nav.adapter = nav
        b.back.setOnClickListener { vm.settingsPage.value = null }
        back = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, false) {
            if (!wide && vm.settingsPage.value != null) vm.settingsPage.value = null else vm.closeSettings()
        }

        fun render() {
            val settings = Asl.settings.settings.value
            val page = vm.settingsPage.value ?: "startup".takeIf { wide }
            val kind = page?.substringBefore(':')
            val arg = page?.substringAfter(':', "").orEmpty()
            b.nav.isVisible = wide || page == null
            b.nav.updateLayoutParams { width = if (wide) (280 * density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT }
            b.divider.isVisible = wide
            b.pane.isVisible = page != null
            b.back.isVisible = !wide
            b.title.text = when (kind) {
                "profile" -> settings.profiles.list.firstOrNull { it.guid == arg }?.name ?: getString(R.string.settings_profile)
                "scheme" -> arg
                else -> NavEntries.firstOrNull { it.key == kind }?.let { getString(it.label) }
            }
            nav.submitList(
                NavGroup.entries.flatMap { group ->
                    listOfNotNull(
                        NavRow("divider", RowKind.Divider).takeIf { group == NavGroup.Footer },
                        group.title?.let { NavRow("group:$it", RowKind.Header, getString(it)) },
                    ) + NavEntries.filter { it.group == group }.map {
                        NavRow(it.key, RowKind.Item, getString(it.label), it.icon, selected = page == it.key)
                    } + if (group != NavGroup.Profiles) emptyList() else settings.profiles.list.map { p ->
                        NavRow("profile:${p.guid}", RowKind.Item, p.name, glyph = p.glyph, selected = page == "profile:${p.guid}")
                    } + NavRow(ADD_PROFILE, RowKind.Item, getString(R.string.settings_add_profile), R.drawable.ic_settings_add)
                },
            )
            val current = childFragmentManager.findFragmentById(R.id.page)
            if (current?.tag == page || childFragmentManager.isStateSaved) return
            childFragmentManager.commit {
                val next = Pages[kind]?.invoke()?.apply { arguments = bundleOf(ARG to arg) }
                if (next != null) replace(R.id.page, next, page) else current?.let(::remove)
            }
        }

        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val now = v.width >= WIDE_PANE_DP * density
            if (now != wide) {
                wide = now
                v.post { if (this@SettingsFragment.view != null) render() }
            }
        }
        collectWhileStarted(combine(vm.settingsPage, Asl.settings.settings) { _, _ -> }) { render() }
        collectWhileStarted(vm.selected) { onHiddenChanged(isHidden) }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        back?.isEnabled = !hidden && vm.selected.value == SETTINGS_TAB
    }
}
