package dev.asl.ui.gui

import android.view.KeyEvent

/**
 * Android key events → X keysyms (the VNC KeyEvent payload). The Latin-1 codepoints double as
 * keysyms, so printable characters need no table; the rest is the standard X keysym ranges
 * (0xFF00+ for control keys).
 */
object Keysyms {
    /** Modifier keys are their own events; the X server derives Ctrl/Alt/Shift state from them. */
    private val modifiers = mapOf(
        KeyEvent.KEYCODE_SHIFT_LEFT to 0xffe1, KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe1,
        KeyEvent.KEYCODE_CTRL_LEFT to 0xffe3, KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe3,
        KeyEvent.KEYCODE_ALT_LEFT to 0xffe9, KeyEvent.KEYCODE_ALT_RIGHT to 0xffe9,
        KeyEvent.KEYCODE_META_LEFT to 0xffe7, KeyEvent.KEYCODE_META_RIGHT to 0xffe7, // Meta_L
        KeyEvent.KEYCODE_CAPS_LOCK to 0xffe5,
        KeyEvent.KEYCODE_NUM_LOCK to 0xffd7,
    )

    private val codes = mapOf(
        KeyEvent.KEYCODE_ENTER to 0xff0d, // Return
        KeyEvent.KEYCODE_ESCAPE to 0xff1b,
        KeyEvent.KEYCODE_TAB to 0xff09,
        KeyEvent.KEYCODE_DEL to 0xff08, // BackSpace
        KeyEvent.KEYCODE_FORWARD_DEL to 0xffff, // Delete
        KeyEvent.KEYCODE_MOVE_HOME to 0xff50,
        KeyEvent.KEYCODE_DPAD_LEFT to 0xff51,
        KeyEvent.KEYCODE_DPAD_UP to 0xff52,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0xff53,
        KeyEvent.KEYCODE_DPAD_DOWN to 0xff54,
        KeyEvent.KEYCODE_PAGE_UP to 0xff55,
        KeyEvent.KEYCODE_PAGE_DOWN to 0xff56,
        KeyEvent.KEYCODE_MOVE_END to 0xff57,
        KeyEvent.KEYCODE_INSERT to 0xff63,
        KeyEvent.KEYCODE_SPACE to 0x20,
        KeyEvent.KEYCODE_F1 to 0xffbe,
        KeyEvent.KEYCODE_F2 to 0xffbf,
        KeyEvent.KEYCODE_F3 to 0xffc0,
        KeyEvent.KEYCODE_F4 to 0xffc1,
        KeyEvent.KEYCODE_F5 to 0xffc2,
        KeyEvent.KEYCODE_F6 to 0xffc3,
        KeyEvent.KEYCODE_F7 to 0xffc4,
        KeyEvent.KEYCODE_F8 to 0xffc5,
        KeyEvent.KEYCODE_F9 to 0xffc6,
        KeyEvent.KEYCODE_F10 to 0xffc7,
        KeyEvent.KEYCODE_F11 to 0xffc8,
        KeyEvent.KEYCODE_F12 to 0xffc9,
    )

    fun forKeyEvent(e: KeyEvent): Int? {
        modifiers[e.keyCode]?.let { return it }
        codes[e.keyCode]?.let { return it }
        // Ctrl/Alt/Shift are already separate X modifier events; the letter's keysym needs only
        // its shift state, so other meta state must not distort the character.
        val shift = e.metaState and KeyEvent.META_SHIFT_MASK
        val caps = e.metaState and KeyEvent.META_CAPS_LOCK_MASK
        val unicode = e.getUnicodeChar(shift or caps)
        return when {
            unicode in 0x20..0xff -> unicode // Latin-1 keysyms mirror the codepoints
            unicode > 0 -> 0x01000000 + unicode // Unicode keysym range
            else -> null
        }
    }
}
