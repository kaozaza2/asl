package dev.asl.ui.gui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * A GUI window: the distro's desktop as a scaled bitmap. The RFB decoder owns the pixels and
 * calls in from the IO thread; [postInvalidate] marshals repaints back to this thread, and
 * touch/key events translate into VNC pointer/key events.
 */
class GuiSurface(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var client: RfbClient? = null

    /** Shown until the first framebuffer update, and any error afterwards. */
    var status: String = context.getString(R.string.gui_connecting)
        set(value) {
            field = value
            postInvalidate()
        }

    private var bitmap: Bitmap? = null
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
    }

    fun onScreen(width: Int, height: Int) {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        computeLayout()
        postInvalidate()
    }

    fun onUpdate(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
        val b = bitmap ?: return
        // The decoder hands 0x00RRGGBB; making alpha opaque leaves a blit-ready bitmap.
        val opaque = IntArray(pixels.size) { pixels[it] or -0x1000000 }
        b.setPixels(opaque, 0, width, x, y, width, height)
        postInvalidate()
    }

    fun onCopyRect(srcX: Int, srcY: Int, x: Int, y: Int, width: Int, height: Int) {
        val b = bitmap ?: return
        val block = IntArray(width * height)
        b.getPixels(block, 0, width, srcX, srcY, width, height)
        b.setPixels(block, 0, width, x, y, width, height)
        postInvalidate()
    }

    fun onClose(reason: String?) {
        status = when (reason) {
            null -> context.getString(R.string.gui_closed)
            else -> context.getString(R.string.gui_disconnected, reason)
        }
        client = null
    }

    /** Fit the desktop into the window at up to 2x, keeping its aspect ratio, like a VNC viewer. */
    private fun computeLayout() {
        val b = bitmap ?: return
        if (width == 0 || height == 0 || b.width == 0) return
        scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height).coerceIn(0.2f, 2f)
        offsetX = (width - b.width * scale) / 2
        offsetY = (height - b.height * scale) / 2
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        if (b == null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14 * resources.displayMetrics.density
                textAlign = Paint.Align.CENTER
                color = -0x333334
            }
            val lines = status.split("\n")
            val lineHeight = paint.textSize * 1.5f
            val top = height / 2f - (lines.size - 1) * lineHeight / 2
            lines.forEachIndexed { i, line -> canvas.drawText(line, width / 2f, top + i * lineHeight, paint) }
            return
        }
        canvas.drawBitmap(b, null, RectF(offsetX, offsetY, offsetX + b.width * scale, offsetY + b.height * scale), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = bitmap ?: return false
        val rfb = client ?: return false
        // Primary pointer only: multi-touch gestures (pinch-zoom, right-click) are follow-ups.
        val x = ((event.x - offsetX) / scale).toInt().coerceIn(0, b.width - 1)
        val y = ((event.y - offsetY) / scale).toInt().coerceIn(0, b.height - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> rfb.pointer(x, y, MASK_CLICK)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> rfb.pointer(x, y, 0)
            // Extra fingers come and go; the primary button stays pressed until the main release.
            MotionEvent.ACTION_POINTER_DOWN -> Unit
            MotionEvent.ACTION_POINTER_UP -> if (event.actionIndex == 0) rfb.pointer(x, y, 0)
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val b = bitmap
        val rfb = client
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && b != null && rfb != null) {
            // Mouse wheel: AXIS_VSCROLL is negative for a scroll up (RFB button 4) and positive
            // for a scroll down (button 5), sent as a press+release pair.
            val mask = if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0) MASK_WHEEL_UP else MASK_WHEEL_DOWN
            val x = ((event.x - offsetX) / scale).toInt().coerceIn(0, b.width - 1)
            val y = ((event.y - offsetY) / scale).toInt().coerceIn(0, b.height - 1)
            rfb.pointer(x, y, mask)
            rfb.pointer(x, y, 0)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // Keys need a hardware keyboard; IME text input for GUI windows is a follow-up feature.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = Keysyms.forKeyEvent(event) ?: return super.onKeyDown(keyCode, event)
        client?.key(true, keysym)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = Keysyms.forKeyEvent(event) ?: return super.onKeyUp(keyCode, event)
        client?.key(false, keysym)
        return true
    }

    private companion object {
        // RFB pointer-event button masks, per the RFB 3.8 spec: bit 0 button 1 (left), 3→ up wheel.
        private const val MASK_CLICK = 1
        private const val MASK_WHEEL_UP = 8
        private const val MASK_WHEEL_DOWN = 16
    }
}
