package dev.asl.vm

import android.os.ParcelFileDescriptor
import dev.asl.core.AgentConnection
import dev.asl.core.requireOk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Performs the PROTOCOL.md header handshake; [output] is chunked (`[u32 len][bytes]`) when the svc is import or forward. */
internal class VsockConnection(pfd: ParcelFileDescriptor, header: JsonObject) : AgentConnection {
    override val fd: FileDescriptor = pfd.fileDescriptor
    override val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
    private val raw = ParcelFileDescriptor.AutoCloseOutputStream(pfd.dup())
    private val chunked = header["svc"]?.jsonPrimitive?.contentOrNull in setOf("import", "forward")
    override val output: OutputStream = if (!chunked) raw else object : OutputStream() {
        private var buf = ByteArray(0)
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val chunk = synchronized(this) {
                if (buf.size != len + 4) buf = ByteArray(len + 4)
                ByteBuffer.wrap(buf).putInt(len).put(b, off, len).array()
            }
            raw.write(chunk)
        }
        override fun close() = raw.close()
    }
    override val reply: JsonObject

    init {
        try {
            raw.write("$header\n".toByteArray())
            // Byte at a time so nothing past the reply line is consumed from the stream.
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("agent closed the connection during the handshake")
                if (b == '\n'.code) break
                line.write(b)
            }
            reply = Json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject.requireOk()
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    // SELinux denies shutdown() on app vsock sockets, so EOF is the zero-length chunk.
    override fun shutdownOutput() {
        if (!chunked) throw IOException("half-close needs a chunked service (import or forward)")
        raw.write(ByteArray(4))
    }

    // Closing both pfds is what wakes blocked readers.
    override fun close() {
        runCatching { input.close() }
        runCatching { raw.close() }
    }
}
