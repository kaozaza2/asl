package dev.asl.ui.gui

import android.content.Context
import dev.asl.R
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * A GUI window: the distro's desktop as a scaled bitmap. The RFB decoder owns the pixels and
 * calls in from the IO thread; [postInvalidate] marshals repaints back to this thread. Touch
 * input: one finger drags the desktop's mouse, a two-finger pinch scales (the fit-zoom at the
 * focal point), two-finger moves pan a zoomed desktop — the second finger holds the synthetic
 * mouse up so gestures never drag the desktop. Text comes from the IME as key events.
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
    private var fitScale = 1f
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** Viewport scale: the fit-to-window scale times the user's pinch zoom. */
    private val scale get() = fitScale * zoom

    // Gesture state: from the second finger on, touches are anonymous to the desktop.
    private var multiTouch = false
    private var pinching = false
    private var panX = 0f
    private var panY = 0f
    private var panCount = 0
    private var lastX = 0f
    private var lastY = 0f
    private var tapX = 0f
    private var tapY = 0f
    private var tapAt = 0L

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = multiTouch && bitmap != null

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val b = bitmap ?: return false
            val before = scale
            zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            // Keep the pinch focal point over the content point it started on.
            val ratio = scale / before
            offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
            offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
            clampOffsets(b)
            postInvalidate()
            return true
        }
    })

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

    /** Fit the desktop into the window; the user's zoom, if any, stays on top of it. */
    private fun computeLayout() {
        val b = bitmap ?: return
        if (width == 0 || height == 0 || b.width == 0) return
        fitScale = minOf(width.toFloat() / b.width, height.toFloat() / b.height).coerceAtLeast(0.01f)
        clampOffsets(b)
    }

    /** Center the desktop when it fits; pan it between its edges when it overflows. */
    private fun clampOffsets(b: Bitmap) {
        val slackX = width - b.width * scale
        val slackY = height - b.height * scale
        offsetX = if (slackX >= 0) slackX / 2 else offsetX.coerceIn(slackX, 0f)
        offsetY = if (slackY >= 0) slackY / 2 else offsetY.coerceIn(slackY, 0f)
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
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) multiTouch = true
        scaleDetector.onTouchEvent(event)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                lastX = event.x
                lastY = event.y
                tapX = event.x
                tapY = event.y
                tapAt = event.eventTime
                val (x, y) = point(event.x, event.y, b)
                rfb.pointer(x, y, MASK_CLICK)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger always opens a gesture: lift the synthetic mouse first, so
                // pinch and pan never drag the desktop.
                val (x, y) = point(lastX, lastY, b)
                rfb.pointer(x, y, 0)
                lastX = event.x
                lastY = event.y
                panX = event.x
                panY = event.y
                panCount = event.pointerCount
            }

            MotionEvent.ACTION_MOVE -> when {
                scaleDetector.isInProgress -> pinching = true

                multiTouch && event.pointerCount >= 2 -> {
                    // Pan by the midpoint of the first and last (extra fingers join the pan).
                    val midX = (event.getX(0) + event.getX(event.pointerCount - 1)) / 2
                    val midY = (event.getY(0) + event.getY(event.pointerCount - 1)) / 2
                    if (pinching || event.pointerCount != panCount) {
                        pinching = false // the pinch just ended: its last offsets are the new base
                        panCount = event.pointerCount
                    } else {
                        panBy(midX - panX, midY - panY, b)
                    }
                    panX = midX
                    panY = midY
                }

                multiTouch -> Unit // one finger left over from a gesture stays anonymous

                else -> {
                    lastX = event.x
                    lastY = event.y
                    val (x, y) = point(event.x, event.y, b)
                    rfb.pointer(x, y, MASK_CLICK)
                }
            }

            MotionEvent.ACTION_UP -> {
                val (x, y) = point(event.x, event.y, b)
                rfb.pointer(x, y, 0)
                multiTouch = false
                pinching = false
                // A quick, still touch is a tap: take focus and open the keyboard for the IME.
                val dx = event.x - tapX
                val dy = event.y - tapY
                if (event.eventTime - tapAt <= tapTimeout && dx * dx + dy * dy <= touchSlop * touchSlop) openKeyboard()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Two fingers left the gesture as one lowered: a remaining finger stays anonymous.
            }

            MotionEvent.ACTION_CANCEL -> {
                val (x, y) = point(lastX, lastY, b)
                rfb.pointer(x, y, 0)
                multiTouch = false
                pinching = false
            }

            else -> return super.onTouchEvent(event)
        }
        return true
    }

    /** Pan by a delta and re-clamp; extents beyond the desktop's edge are dropped. */
    private fun panBy(deltaX: Float, deltaY: Float, b: Bitmap) {
        offsetX += deltaX
        offsetY += deltaY
        clampOffsets(b)
        postInvalidate()
    }

    /** Viewport point → desktop pixel, clamped: the synthetic pointer never leaves the screen. */
    private fun point(viewX: Float, viewY: Float, b: Bitmap): Pair<Int, Int> {
        val x = ((viewX - offsetX) / scale).toInt().coerceIn(0, b.width - 1)
        val y = ((viewY - offsetY) / scale).toInt().coerceIn(0, b.height - 1)
        return x to y
    }

    private fun openKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
    }

    /** The IME becomes a plain event source: no composing and no buffer, just key events. */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (client == null) return null
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return BaseInputConnection(this, false)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val b = bitmap
        val rfb = client
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && b != null && rfb != null) {
            // Mouse wheel: AXIS_VSCROLL is negative for a scroll up (RFB button 4) and positive
            // for a scroll down (button 5), sent as a press+release pair.
            val mask = if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0) MASK_WHEEL_UP else MASK_WHEEL_DOWN
            val (x, y) = point(event.x, event.y, b)
            rfb.pointer(x, y, mask)
            rfb.pointer(x, y, 0)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // Keys from hardware keyboards and the IME (via the BaseInputConnection) both land here.
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
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 4f
    }
}
