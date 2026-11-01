package dev.asl.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import com.google.android.material.color.MaterialColors
import dev.asl.R
import dev.asl.core.Asl
import dev.asl.core.ColorScheme
import dev.asl.core.ProfileAppearance
import dev.asl.databinding.ItemSettingsColorBinding
import dev.asl.databinding.ItemSettingsSchemeBinding
import dev.asl.databinding.SettingsListBinding
import dev.asl.databinding.SettingsSchemeHeaderBinding
import dev.asl.databinding.SettingsSchemesHeaderBinding
import dev.asl.settings.BuiltInSchemes
import dev.asl.settings.SettingsJson
import dev.asl.ui.MainViewModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Every color of a scheme in settings.json order; the last 16 are the ANSI palette. */
private val SchemeColors = listOf(
    ColorScheme::background, ColorScheme::foreground, ColorScheme::cursorColor, ColorScheme::selectionBackground,
    ColorScheme::black, ColorScheme::red, ColorScheme::green, ColorScheme::yellow,
    ColorScheme::blue, ColorScheme::purple, ColorScheme::cyan, ColorScheme::white,
    ColorScheme::brightBlack, ColorScheme::brightRed, ColorScheme::brightGreen, ColorScheme::brightYellow,
    ColorScheme::brightBlue, ColorScheme::brightPurple, ColorScheme::brightCyan, ColorScheme::brightWhite,
)

private val HexColor = Regex("#[0-9A-Fa-f]{6}")

private fun hexColor(hex: String) = runCatching { hex.toColorInt() }.getOrDefault(Color.TRANSPARENT)

private fun uniqueSchemeName(base: String, schemes: List<ColorScheme>) =
    generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { name -> schemes.none { it.name == name } }

/** A scheme's background with a sample prompt and its 16 ANSI colors. */
class SchemeSwatchView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var scheme: ColorScheme? = null
        set(value) {
            field = value
            invalidate()
        }
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (56 * dp + 2 * paint.fontSpacing).toInt())

    override fun onDraw(canvas: Canvas) {
        val s = scheme ?: return
        val pad = 12 * dp
        paint.color = hexColor(s.background)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6 * dp, 6 * dp, paint)
        listOf(
            listOf(s.brightGreen to "user@asl", s.foreground to ":", s.brightBlue to "~", s.foreground to "$ ls"),
            listOf(s.brightBlue to "bin  ", s.green to "run.sh  ", s.red to "app.tar.gz"),
        ).forEachIndexed { line, segments ->
            var x = pad
            val y = pad - paint.ascent() + line * paint.fontSpacing
            segments.forEach { (color, text) ->
                paint.color = hexColor(color)
                canvas.drawText(text, x, y, paint)
                x += paint.measureText(text)
            }
        }
        val cell = (width - 2 * pad) / 8
        SchemeColors.takeLast(16).chunked(8).forEachIndexed { row, colors ->
            val top = pad + 2 * paint.fontSpacing + (6 + row * 16) * dp
            colors.forEachIndexed { i, color ->
                paint.color = hexColor(color.get(s))
                canvas.drawRect(pad + i * cell, top, pad + (i + 1) * cell, top + 10 * dp, paint)
            }
        }
    }
}

class SchemesFragment : Fragment(R.layout.settings_list) {
    private val vm by activityViewModels<MainViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = SettingsListBinding.bind(view).list
        val header = SettingsSchemesHeaderBinding.inflate(layoutInflater, list, false)
        header.add.setOnClickListener {
            val name = uniqueSchemeName(getString(R.string.settings_scheme_base_name), Asl.settings.settings.value.schemes)
            Asl.settings.update { it.copy(schemes = it.schemes + BuiltInSchemes.first().copy(name = name)) }
            vm.settingsPage.value = "scheme:$name"
        }
        val items = ItemAdapter(ItemSettingsSchemeBinding::inflate, { row: Pair<ColorScheme, String> -> row.first.name }) { (scheme, tags) ->
            name.text = scheme.name
            this.tags.text = tags
            this.tags.isVisible = tags.isNotEmpty()
            swatch.scheme = scheme
            root.setOnClickListener { vm.settingsPage.value = "scheme:${scheme.name}" }
        }
        list.itemAnimator = null
        list.adapter = ConcatAdapter(ViewAdapter(header.root), items)
        collectWhileStarted(Asl.settings.settings) { s ->
            items.submitList(
                s.schemes.map { scheme ->
                    scheme to listOfNotNull(
                        getString(R.string.settings_tag_default).takeIf { scheme.name == s.profiles.defaults.colorScheme },
                        getString(R.string.settings_tag_builtin).takeIf { BuiltInSchemes.any { it.name == scheme.name } },
                    ).joinToString(" · ")
                },
            )
        }
    }
}

private data class ColorRow(val key: String, val value: String, val editable: Boolean)

class SchemeFragment : Fragment(R.layout.settings_list) {
    private val vm by activityViewModels<MainViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val name = requireArguments().getString(ARG).orEmpty()
        val builtIn = BuiltInSchemes.any { it.name == name }
        val list = SettingsListBinding.bind(view).list
        val header = SettingsSchemeHeaderBinding.inflate(layoutInflater, list, false)
        header.note.isVisible = builtIn
        header.rename.isVisible = !builtIn
        header.delete.isVisible = !builtIn
        header.setDefault.setOnClickListener {
            Asl.settings.update { it.copy(profiles = it.profiles.copy(defaults = it.profiles.defaults.copy(colorScheme = name))) }
        }
        header.duplicate.setOnClickListener {
            val s = Asl.settings.settings.value
            val scheme = s.schemes.firstOrNull { it.name == name } ?: return@setOnClickListener
            val copy = uniqueSchemeName(getString(R.string.settings_copy_name, name), s.schemes)
            Asl.settings.update { it.copy(schemes = it.schemes + scheme.copy(name = copy)) }
            vm.settingsPage.value = "scheme:$copy"
        }
        header.rename.setOnClickListener {
            inputDialog(
                getString(R.string.settings_rename_scheme), getString(R.string.settings_name), name, getString(R.string.settings_rename),
                validate = { n -> getString(R.string.settings_scheme_exists).takeIf { n != name && Asl.settings.settings.value.schemes.any { it.name == n } } },
            ) { newName ->
                Asl.settings.update { s ->
                    val rename = { a: ProfileAppearance -> if (a.colorScheme == name) a.copy(colorScheme = newName) else a }
                    s.copy(
                        schemes = s.schemes.map { if (it.name == name) it.copy(name = newName) else it },
                        profiles = s.profiles.copy(
                            defaults = rename(s.profiles.defaults),
                            list = s.profiles.list.map { p -> p.copy(appearance = p.appearance?.let(rename)) },
                        ),
                    )
                }
                vm.settingsPage.value = "scheme:$newName"
            }
        }
        header.delete.setOnClickListener {
            confirmDialog(getString(R.string.settings_delete_scheme_title), getString(R.string.settings_delete_scheme_message, name), getString(R.string.settings_delete)) {
                Asl.settings.update { s -> s.copy(schemes = s.schemes.filter { it.name != name }) }
                vm.settingsPage.value = "schemes"
            }
        }
        val items = ItemAdapter(ItemSettingsColorBinding::inflate, ColorRow::key) { row ->
            label.text = row.key.replace(Regex("[A-Z]")) { " " + it.value.lowercase() }.replaceFirstChar { it.uppercase() }
            swatch.background = GradientDrawable().apply {
                cornerRadius = 4 * root.resources.displayMetrics.density
                setColor(hexColor(row.value))
                setStroke(root.resources.displayMetrics.density.toInt(), MaterialColors.getColor(root, com.google.android.material.R.attr.colorOutline))
            }
            edit.isEnabled = row.editable
            (edit.tag as? TextWatcher)?.let(edit::removeTextChangedListener)
            if (!edit.text.toString().equals(row.value, ignoreCase = true)) edit.setText(row.value)
            layout.error = null
            edit.tag = edit.doAfterTextChanged { text ->
                val hex = text.toString()
                layout.error = getString(R.string.settings_hex_format).takeUnless { HexColor.matches(hex) }
                if (HexColor.matches(hex)) {
                    Asl.settings.update { s ->
                        s.copy(schemes = s.schemes.map { old ->
                            if (old.name != name) old else SettingsJson.decodeFromJsonElement<ColorScheme>(
                                JsonObject(SettingsJson.encodeToJsonElement(old).jsonObject + (row.key to JsonPrimitive(hex.uppercase()))),
                            )
                        })
                    }
                }
            }
        }
        list.itemAnimator = null
        list.adapter = ConcatAdapter(ViewAdapter(header.root), items)
        collectWhileStarted(Asl.settings.settings) { s ->
            val scheme = s.schemes.firstOrNull { it.name == name }
            header.swatch.scheme = scheme
            header.swatch.isVisible = scheme != null
            header.actions.isVisible = scheme != null
            header.colorsTitle.isVisible = scheme != null
            if (scheme == null) {
                header.note.setText(R.string.settings_scheme_missing)
                header.note.isVisible = true
            }
            items.submitList(scheme?.let { SchemeColors.map { p -> ColorRow(p.name, p.get(it), !builtIn) } }.orEmpty())
        }
    }
}
