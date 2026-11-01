package dev.asl.ui.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
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

@SuppressLint("ViewConstructor")
class TerminalSurface(
    context: Context,
    private val tab: TerminalTab,
    private val latches: Latches,
    private val onExitedEnter: () -> Unit,
    val onBell: () -> Unit,
) : TerminalView(context, null), TerminalViewClient {

    interface Latches {
        fun consumeCtrl(): Boolean
        fun consumeAlt(): Boolean
    }

    var cursorStyle = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        private set
    private var scheme: ColorScheme? = null
    private var fontSize = 12f
    private var applied: Pair<ProfileAppearance, ColorScheme>? = null

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
        val face = Typeface.create(appearance.fontFace, Typeface.NORMAL)
        setTypeface(if (face == Typeface.DEFAULT) Typeface.MONOSPACE else face)
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
        val colors = emulator.mColors.mCurrentColors
        ansi.forEachIndexed { i, c -> colors[i] = Color.parseColor(c) }
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = Color.parseColor(s.foreground)
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = Color.parseColor(s.background)
        colors[TextStyle.COLOR_INDEX_CURSOR] = Color.parseColor(s.cursorColor)
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
        if (session.isRunning || keyCode != KeyEvent.KEYCODE_ENTER) return false
        onExitedEnter()
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
    // Char-based input keeps IME prediction and autocorrect out of the shell.
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
}
