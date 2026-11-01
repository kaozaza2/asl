package dev.asl.vm

import dev.asl.core.AgentConnection
import dev.asl.core.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer

/** The `session` service framing: `[u8 type][u32 len][payload]`, wire type = ordinal. */
internal class AgentSession(private val conn: AgentConnection, scope: CoroutineScope) : TerminalSession {
    private enum class Frame { DATA, RESIZE, EOF, STDERR, EXIT }

    private val received = Channel<ByteArray>(Channel.UNLIMITED)
    private val outgoing = Channel<Pair<Frame, ByteArray>>(Channel.UNLIMITED)

    override val output: Flow<ByteArray> = received.receiveAsFlow()
    override val exitCode = CompletableDeferred<Int>()

    init {
        scope.launch {
            runCatching {
                for ((type, payload) in outgoing) {
                    conn.output.write(ByteBuffer.allocate(5 + payload.size).put(type.ordinal.toByte()).putInt(payload.size).put(payload).array())
                }
            }
            conn.close()
        }
        scope.launch {
            val input = DataInputStream(BufferedInputStream(conn.input))
            runCatching {
                while (true) {
                    val type = Frame.entries[input.readUnsignedByte()]
                    val payload = ByteArray(input.readInt()).also { input.readFully(it) }
                    when (type) {
                        Frame.DATA, Frame.STDERR -> received.send(payload)
                        Frame.EXIT -> { exitCode.complete(ByteBuffer.wrap(payload).int); break }
                        else -> Unit
                    }
                }
            }
            exitCode.complete(-1)
            received.close()
            outgoing.close()
        }
    }

    override fun write(data: ByteArray) {
        outgoing.trySend(Frame.DATA to data)
    }

    override fun resize(cols: Int, rows: Int) {
        outgoing.trySend(Frame.RESIZE to ByteBuffer.allocate(4).putShort(cols.toShort()).putShort(rows.toShort()).array())
    }

    override fun close() {
        outgoing.trySend(Frame.EOF to ByteArray(0))
        outgoing.close()
    }
}
