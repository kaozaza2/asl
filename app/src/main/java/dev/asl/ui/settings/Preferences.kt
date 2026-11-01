package dev.asl.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import dev.asl.R
import dev.asl.core.Asl
import dev.asl.core.AslSettings
import dev.asl.core.Distro
import dev.asl.ui.MainViewModel
import kotlinx.coroutines.flow.combine

/** One setting: how to read it from and write it into [AslSettings], plus how its editor looks. */
class Field(
    val get: (AslSettings) -> Any?,
    val set: (AslSettings, Any?) -> AslSettings,
    val range: IntRange? = null,
    val suffix: String = "",
    var hint: String? = null,
    val monospace: Boolean = false,
)

fun intField(range: IntRange, suffix: String = "", get: (AslSettings) -> Int, set: (AslSettings, Int) -> AslSettings) =
    Field(get, { s, v -> v.toString().toIntOrNull()?.takeIf { it in range }?.let { set(s, it) } ?: s }, range, suffix)

fun textField(hint: String? = null, monospace: Boolean = false, get: (AslSettings) -> String?, set: (AslSettings, String?) -> AslSettings) =
    Field(get, { s, v -> set(s, (v as String?)?.ifBlank { null }) }, hint = hint, monospace = monospace)

private class SettingsStore(private val fields: Map<String, Field>) : PreferenceDataStore() {
    private fun value(key: String) = fields[key]?.get?.invoke(Asl.settings.settings.value)

    private fun put(key: String, value: Any?) {
        val field = fields[key] ?: return
        Asl.settings.update { field.set(it, value) }
    }

    override fun getString(key: String, defValue: String?) = value(key)?.toString() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = value(key) as? Boolean ?: defValue
    override fun getInt(key: String, defValue: Int) = value(key) as? Int ?: defValue
    override fun putString(key: String, value: String?) = put(key, value)
    override fun putBoolean(key: String, value: Boolean) = put(key, value)
    override fun putInt(key: String, value: Int) = put(key, value)
}

/** A preference page whose keys are [Field]s of settings.json, kept in sync with every change to it. */
abstract class StorePreferenceFragment : PreferenceFragmentCompat() {
    protected val vm by activityViewModels<MainViewModel>()
    protected abstract val xml: IntArray
    protected abstract fun fields(): Map<String, Field>
    protected lateinit var table: Map<String, Field>
    protected val descriptions = mutableMapOf<String, CharSequence?>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        table = fields()
        preferenceManager.preferenceDataStore = SettingsStore(table)
        setPreferencesFromResource(xml.first(), rootKey)
        xml.drop(1).forEach { addPreferencesFromResource(it) }
        table.forEach { (key, field) ->
            val p = findPreference<Preference>(key) ?: return@forEach
            descriptions[key] = p.summary
            (p as? EditTextPreference)?.setOnBindEditTextListener { edit ->
                if (field.range != null) edit.inputType = InputType.TYPE_CLASS_NUMBER
                if (field.monospace) edit.typeface = Typeface.MONOSPACE
                edit.setSelection(edit.length())
            }
            field.range?.let { range ->
                p.setOnPreferenceChangeListener { _, v ->
                    (v.toString().toIntOrNull() in range).also { ok ->
                        if (!ok) Toast.makeText(requireContext(), getString(R.string.settings_out_of_range, range.first, range.last), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (p is EditTextPreference || p is ListPreference) {
                p.summaryProvider = Preference.SummaryProvider<Preference> { pref ->
                    val value = when (pref) {
                        is ListPreference -> pref.entry
                        is EditTextPreference -> pref.text?.takeIf { it.isNotBlank() }?.let { it + field.suffix } ?: field.hint
                        else -> null
                    }
                    listOfNotNull(value, descriptions[key] ?: field.range?.let { "${it.first}–${it.last}" }).joinToString("\n")
                }
            }
        }
        onPreferencesCreated()
    }

    protected open fun onPreferencesCreated() {}

    /** Called before the preference values are refreshed, to update entries and visibility. */
    protected open fun bind(s: AslSettings, distros: List<Distro>) {}

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectWhileStarted(combine(Asl.settings.settings, Asl.distros.distros, ::Pair)) { (s, distros) ->
            bind(s, distros)
            table.forEach { (key, field) ->
                val p = findPreference<Preference>(key) ?: return@forEach
                val value = field.get(s)
                when (p) {
                    is TwoStatePreference -> if (p.isChecked != value) p.isChecked = value as Boolean
                    is EditTextPreference -> if (p.text != value?.toString()) p.text = value?.toString()
                    is ListPreference -> if (p.value != value?.toString()) p.value = value?.toString()
                    is SeekBarPreference -> if (p.value != value) p.value = value as Int
                }
                p.summaryProvider = p.summaryProvider // Self-assignment forces Preference rebinds; androidx.preference exposes no public notifyChanged.
            }
        }
    }
}

/** A preference row with a custom layout, bound by [bind] each time it is shown or [refresh]ed. */
class ViewPreference(context: Context, layout: Int, private val bind: (View) -> Unit) : Preference(context) {
    init {
        layoutResource = layout
        isSelectable = false
        isPersistent = false
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        bind(holder.itemView)
    }

    fun refresh() = notifyChanged()
}
