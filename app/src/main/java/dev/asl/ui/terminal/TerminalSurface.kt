package dev.asl.ui.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.asl.core.ColorScheme
import dev.asl.core.ProfileAppearance

/** Windows Terminal cursorShape → Termux cursor style. */
private val CursorStyles = mapOf(
    "bar" to TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR,
    "underscore" to TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE,
    "doubleUnderscore" to TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE,
    "vintage" to TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE,
    "filledBox" to TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK,
    "emptyBox" to TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK,
)

/** Extra-keys row names → Android key codes, encoded by Termux's KeyHandler. */
val ExtraKeyCodes = mapOf(
    "esc" to KeyEvent.KEYCODE_ESCAPE, "tab" to KeyEvent.KEYCODE_TAB,
    "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
    "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "home" to KeyEvent.KEYCODE_MOVE_HOME, "end" to KeyEvent.KEYCODE_MOVE_END,
    "pgup" to KeyEvent.KEYCODE_PAGE_UP, "pgdn" to KeyEvent.KEYCODE_PAGE_DOWN,
)

/**
 * The subtype's language: its BCP 47 [InputMethodSubtype.getLanguageTag], or for input methods that leave
 * that empty, the legacy locale string. `locale` is deprecated in favor of `languageTag`, which has no
 * fallback of its own, so it stays here only for those input methods.
 */
private val InputMethodSubtype.language: String
    @Suppress("DEPRECATION")
    get() = languageTag.ifEmpty { locale }

@SuppressLint("ViewConstructor")
class TerminalSurface(
    context: Context,
    private val tab: TerminalTab,
    private val latches: Latches,
    private val onExitedEnter: () -> Unit,
    val onBell: () -> Unit,
) : TerminalView(context, null), TerminalViewClient, LogViewClient {

    interface Latches {
        fun consumeCtrl(): Boolean
        fun consumeAlt(): Boolean
    }

    var cursorStyle = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        private set
    private var scheme: ColorScheme? = null
    private var fontSize = 12f
    private var applied: Pair<ProfileAppearance, ColorScheme>? = null

    // Samsung Keyboard maps physical keys to Thai only inside text fields, so the terminal applies Android's own
    // Kedmanee layout (assets/keyboard, from AOSP InputDevices) while the current input language is Thai.
    private val thaiKeys: Map<Int, Pair<Int, Int>> by lazy {
        val value = Regex("""'(\\u[0-9a-fA-F]{4}|\\.|.)'""")
        val decode = { v: String -> if (v.startsWith("\\u")) v.substring(2).toInt(16) else v.last().code }
        val keys = mutableMapOf<Int, Pair<Int, Int>>()
        var key = KeyEvent.KEYCODE_UNKNOWN
        var base = 0
        context.assets.open("keyboard/thai_kedmanee.kcm").bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            val v = value.find(line)?.groupValues?.get(1)?.let(decode)
            when {
                line.startsWith("key ") -> key = KeyEvent.keyCodeFromString("KEYCODE_" + line.split(' ')[1])
                line.startsWith("base:") && v != null -> base = v
                line.startsWith("shift") && v != null && key != KeyEvent.KEYCODE_UNKNOWN -> keys[key] = base to v
            }
        }
        keys
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        setTerminalViewClient(this)
        setTextSize(px(fontSize))
        tab.view = this
        attachSession(tab.terminal)
    }

    fun configure(appearance: ProfileAppearance, scheme: ColorScheme) {
        if (applied == appearance to scheme) return
        applied = appearance to scheme
        this.scheme = scheme
        cursorStyle = CursorStyles[appearance.cursorShape] ?: TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        setTypeface(context.typeface(appearance.fontFace))
        fontSize = appearance.fontSize.toFloat()
        setTextSize(px(fontSize))
        applyColors()
    }

    fun show(visible: Boolean) {
        val target = if (visible) VISIBLE else INVISIBLE
        if (visibility == target) return
        visibility = target
        if (visible) requestFocus()
    }

    fun sendKey(name: String) {
        val emulator = mEmulator ?: return
        val mods = (if (latches.consumeCtrl()) KeyHandler.KEYMOD_CTRL else 0) or (if (latches.consumeAlt()) KeyHandler.KEYMOD_ALT else 0)
        val code = KeyHandler.getCode(ExtraKeyCodes.getValue(name), mods, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
        code?.let { tab.terminal.write(it.toByteArray(), 0, it.length) }
    }

    fun typeText(text: String) = text.codePoints().forEach { inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, it, false, false) }

    fun copy(text: String) =
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ASL", text))

    fun pasteClipboard() {
        context.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
            ?.let { mEmulator?.paste(it) }
    }

    private fun applyColors() {
        val s = scheme ?: return
        val emulator = mEmulator ?: return
        val ansi = listOf(
            s.black, s.red, s.green, s.yellow, s.blue, s.purple, s.cyan, s.white,
            s.brightBlack, s.brightRed, s.brightGreen, s.brightYellow, s.brightBlue, s.brightPurple, s.brightCyan, s.brightWhite,
        )
        val colors = emulator.mColors
        // Register the scheme as the instance defaults as well, so terminal resets (RIS/"reset", OSC 104
        // and OSC 110-112) restore the configured scheme instead of the builtin theme's default colors.
        ansi.forEachIndexed { i, c -> colors.setDefaultColor(i, Color.parseColor(c)) }
        colors.setDefaultColor(TextStyle.COLOR_INDEX_FOREGROUND, Color.parseColor(s.foreground))
        colors.setDefaultColor(TextStyle.COLOR_INDEX_BACKGROUND, Color.parseColor(s.background))
        colors.setDefaultColor(TextStyle.COLOR_INDEX_CURSOR, Color.parseColor(s.cursorColor))
        emulator.setCursorStyle()
        invalidate()
    }

    private fun px(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics).toInt()

    override fun onEmulatorSet() {
        applyColors()
        setTerminalCursorBlinkerRate(500)
        setTerminalCursorBlinkerState(true, true)
    }

    override fun onScale(scale: Float): Float {
        if (scale in 0.9f..1.1f) return scale
        fontSize = (fontSize + if (scale > 1f) 1f else -1f).coerceIn(6f, 48f)
        setTextSize(px(fontSize))
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (!session.isRunning) {
            if (keyCode != KeyEvent.KEYCODE_ENTER) return false
            onExitedEnter()
            return true
        }
        val physical = e.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0 && !e.isCtrlPressed && !e.isAltPressed && !e.isMetaPressed
        val subtype = context.getSystemService(InputMethodManager::class.java).currentInputMethodSubtype
        if (!physical || subtype?.language?.startsWith("th") != true) return false
        val (base, shifted) = thaiKeys[keyCode] ?: return false
        session.writeCodePoint(false, if (e.isShiftPressed || e.isCapsLockOn) shifted else base)
        return true
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (session.isRunning || codePoint != '\r'.code && codePoint != '\n'.code) return false
        onExitedEnter()
        return true
    }

    override fun readControlKey() = latches.consumeCtrl()
    override fun readAltKey() = latches.consumeAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun shouldBackButtonBeMappedToEscape() = false
    // Char-based input is a visible-password field, where keyboards lock to Latin layouts (no Thai).
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
}
