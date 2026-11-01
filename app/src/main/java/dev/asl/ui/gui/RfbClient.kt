package dev.asl.ui.gui

import dev.asl.core.AgentConnection
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RFB 3.8 client (the VNC wire protocol) over an agent `forward` channel to the distro's X server. */
class RfbClient(
    private val conn: AgentConnection,
    private val listener: Listener,
) {
    interface Listener {
        /** The desktop size, before the first update. */
        fun onScreen(width: Int, height: Int, name: String)
        /** An update rectangle's 0x00RRGGBB pixels (alpha is implied opaque), row-major. */
        fun onUpdate(x: Int, y: Int, width: Int, height: Int, pixels: IntArray)
        /** A copied rectangle: the server block at (srcX, srcY) reads as the client block at (x, y). */
        fun onCopyRect(srcX: Int, srcY: Int, x: Int, y: Int, width: Int, height: Int)
        /** The stream ended; [reason] is null for a clean server exit. */
        fun onClosed(reason: String?)
    }

    private val input = DataInputStream(BufferedInputStream(conn.input))
    private val output = DataOutputStream(conn.output)

    /** Blocks until the stream ends (the update loop) or the handshake fails; run on Dispatchers.IO. */
    fun run() {
        val (width, height) = handshake()
        updates(width, height)
    }

    private fun handshake(): Pair<Int, Int> {
        val minor = minorVersion(readBytes(12))
        if (minor > 8) throw IOException("unsupported server protocol RFB 003.$minor")
        output.write("RFB 003.008\n".toByteArray())
        val types = input.readUnsignedByte()
        val chosen = (1..types).map { input.readUnsignedByte() }.singleOrNull { it == TYPE_NONE }
        if (chosen == null) throw IOException("the X server asks for VNC authentication, but ASL GUI windows use none")
        output.writeByte(chosen)
        if (minor >= 8 && input.readInt() != 0) throw IOException("VNC authentication failed")
        output.writeByte(1) // ClientInit: share the desktop, so several windows render the same screen
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        input.readFully(ByteArray(16)) // ServerInit pixel format; the SetPixelFormat below overrides it
        val name = String(readBytes(input.readInt()), Charsets.ISO_8859_1)
        listener.onScreen(width, height, name)
        // Pixel format: little-endian 32bpp true color, red at bits 24-31, so each read paints
        // 0x00RRGGBB that the surface turns opaque.
        val format = byteArrayOf(
            0x20, 0x20, 0, 1, // bits-per-pixel (32), depth (32), big-endian (no), true-color (yes)
            -1, -1, -1, -1, -1, -1, // red, green, blue maxima (255 in 16 bits, little-endian)
            16, 8, 0, // red, green, blue shifts inside the 32-bit word
            0, 0, 0, // padding
        )
        output.write(0) // SetPixelFormat
        output.write(ByteArray(3))
        output.write(format)
        // Raw is the correctness fallback; CopyRect keeps scrolling cost down.
        output.write(2)
        output.write(ByteArray(2))
        output.writeShort(2)
        output.writeInt(ENCODING_COPYRECT)
        output.writeInt(ENCODING_RAW)
        request(0, 0, width, height, incremental = false)
        return width to height
    }

    private fun updates(width: Int, height: Int) {
        try {
            while (true) {
                input.readUnsignedByte() // FramebufferUpdate
                input.readUnsignedByte() // padding
                repeat(input.readUnsignedShort()) {
                    val x = input.readUnsignedShort()
                    val y = input.readUnsignedShort()
                    val w = input.readUnsignedShort()
                    val h = input.readUnsignedShort()
                    when (val encoding = input.readInt()) {
                        ENCODING_RAW -> {
                            val bytes = ByteArray(w * h * 4)
                            input.readFully(bytes)
                            val pixels = IntArray(w * h)
                            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
                            listener.onUpdate(x, y, w, h, pixels)
                        }
                        ENCODING_COPYRECT -> {
                            val srcX = input.readUnsignedShort()
                            val srcY = input.readUnsignedShort()
                            listener.onCopyRect(srcX, srcY, x, y, w, h)
                        }
                        else -> throw IOException("the server sent unrequested encoding $encoding")
                    }
                }
                request(0, 0, width, height, incremental = true)
            }
        } catch (e: EOFException) {
            listener.onClosed(null)
        } catch (e: IOException) {
            listener.onClosed(e.message ?: e.toString())
        } finally {
            conn.close()
        }
    }

    private fun request(x: Int, y: Int, width: Int, height: Int, incremental: Boolean) {
        // Pointer/key events write from the UI thread, so every writer takes the output lock.
        synchronized(output) {
            output.write(3) // SetFramebufferUpdateRequest
            output.write(if (incremental) 1 else 0)
            output.writeShort(x); output.writeShort(y); output.writeShort(width); output.writeShort(height)
            output.flush()
        }
    }

    fun pointer(x: Int, y: Int, mask: Int) {
        synchronized(output) {
            output.write(5) // PointerEvent
            output.write(mask)
            output.writeShort(x)
            output.writeShort(y)
            output.flush()
        }
    }

    fun key(down: Boolean, keysym: Int) {
        synchronized(output) {
            output.write(4) // KeyEvent
            output.write(if (down) 1 else 0)
            output.writeShort(0)
            output.writeInt(keysym)
            output.flush()
        }
    }

    fun close() = conn.close()

    private fun readBytes(count: Int): ByteArray = ByteArray(count).also { input.readFully(it) }

    /** "RFB 003.008\n" → 8: bytes[9] is the minor version's ASCII digit ('0'-'9'). */
    private fun minorVersion(bytes: ByteArray): Int {
        if (!String(bytes, Charsets.ISO_8859_1).startsWith("RFB 003.0")) throw IOException("not an RFB server")
        return bytes[9].toInt() - '0'.code
    }

    private companion object {
        private const val TYPE_NONE = 1
        private const val ENCODING_RAW = 0
        private const val ENCODING_COPYRECT = 1
    }
}
