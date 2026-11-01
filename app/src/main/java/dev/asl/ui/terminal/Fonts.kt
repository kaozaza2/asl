package dev.asl.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

// Nerd Fonts "Mono" builds (single-cell icons) shipped in assets/fonts, each with its SIL OFL 1.1 license beside it.
// `replaces` is the upstream family, so settings written for Windows Terminal ("Cascadia Mono") still match.
private class BundledFont(val name: String, val asset: String, val replaces: String)

private val Bundled = listOf(
    BundledFont("CaskaydiaMono Nerd Font Mono", "fonts/CaskaydiaMonoNerdFontMono-Regular.ttf", "Cascadia Mono"),
    BundledFont("JetBrainsMono Nerd Font Mono", "fonts/JetBrainsMonoNerdFontMono-Regular.ttf", "JetBrains Mono"),
    BundledFont("FiraCode Nerd Font Mono", "fonts/FiraCodeNerdFontMono-Regular.ttf", "Fira Code"),
    BundledFont("SauceCodePro Nerd Font Mono", "fonts/SauceCodeProNerdFontMono-Regular.ttf", "Source Code Pro"),
)

val BundledFontNames = Bundled.map { it.name }

private val FontExtensions = setOf("ttf", "otf")

private fun Context.fontsDir() = File(filesDir, "fonts")

/** Fonts the user added with the file picker, by name. */
fun Context.importedFonts(): Map<String, File> =
    fontsDir().listFiles().orEmpty().filter { it.isFile && it.extension in FontExtensions }.associateBy { it.nameWithoutExtension }.toSortedMap()

/** Resolves a profile's fontFace: bundled, then imported, then an installed system font, else system monospace. */
fun Context.typeface(name: String): Typeface =
    Bundled.firstOrNull { name == it.name || name == it.replaces }?.let { Typeface.createFromAsset(assets, it.asset) }
        ?: importedFonts()[name]?.let { Typeface.Builder(it).build() }
        ?: Typeface.create(name, Typeface.NORMAL).takeIf { it != Typeface.DEFAULT }
        ?: Typeface.MONOSPACE

/** Copies a .ttf/.otf from the picker into app storage and returns the name to store as fontFace. */
fun Context.importFont(uri: Uri): String {
    val display = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
    val ext = display.substringAfterLast('.', "").lowercase()
    if (ext !in FontExtensions) throw IOException("Choose a .ttf or .otf font file")
    val name = display.substringBeforeLast('.').ifBlank { "Custom font" }
    val target = File(fontsDir().apply { mkdirs() }, "$name.$ext")
    val staging = File(target.parentFile, "$name.$ext.part")
    contentResolver.openInputStream(uri)?.use { input -> staging.outputStream().use(input::copyTo) } ?: throw IOException("Cannot read $display")
    if (Typeface.Builder(staging).build() == null) {
        staging.delete()
        throw IOException("$display is not a usable font")
    }
    importedFonts()[name]?.delete()
    if (!staging.renameTo(target)) throw IOException("Cannot save $display")
    return name
}
