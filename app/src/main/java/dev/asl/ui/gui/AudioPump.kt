package dev.asl.ui.gui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.asl.core.AgentConnection
import dev.asl.core.Asl
import dev.asl.core.openAgentChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * A GUI window's sound output, the way WSLg keeps audio simple: the guest exports the
 * distro desktop's monitor mix as a raw PCM stream (the display service sets up
 * module-simple-protocol-tcp in the distro's sound daemon), and this pump plays it through
 * an [AudioTrack] over a `forward` channel.
 *
 * Degrades silently: when the distro has no sound stack the port never opens, the retry
 * budget runs out, and the window stays quiet — the display service already logged why.
 */
class AudioPump(private val isAlive: () -> Boolean) {
    private var channel: AgentConnection? = null

    /** Streams until the retry budget is spent or the window closes; call in an IO coroutine. */
    suspend fun run() = withContext(Dispatchers.IO) {
        var budget = RETRIES
        while (isAlive() && budget > 0) {
            val conn = try {
                Asl.vm.openAgentChannel(buildJsonObject {
                    put("svc", "forward"); put("port", AUDIO_PORT)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                budget-- // no stream (or the VM is down): backing off before the next attempt
                delay(RETRY_MS)
                continue
            }
            channel = conn
            budget = RETRIES // a working stream starts a new budget: restarts stay audible
            try {
                play(conn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                budget-- // stream dropped (daemon restart et al): same backoff pool
            } finally {
                channel = null
                conn.close()
            }
            delay(RETRY_MS) // reconnect slowly, whether the stream broke or ended
        }
        channel = null
    }

    /** Stops the current stream; the run loop then leaves through [isAlive]. */
    fun close() {
        channel?.close()
    }

    private fun play(conn: AgentConnection) {
        val minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IOException("audio output unavailable")
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val input = conn.input
            val buffer = ByteArray(CHUNK)
            while (true) {
                var filled = 0
                while (filled < buffer.size) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) return
                    filled += read
                }
                // Blocks whenever playback falls behind: the guest's source is the pace.
                track.write(buffer, 0, buffer.size)
            }
        } finally {
            track.release()
        }
    }

    private companion object {
        /** Same port the guest's module-simple-protocol-tcp listens on (fixed, meant for ASL). */
        const val AUDIO_PORT = 47113L
        const val RATE = 48000 // matches the guest's load-module rate exactly

        /** 50 ms of 48 kHz stereo 16-bit; the AudioTrack buffer plays two of these. */
        const val CHUNK = 9600

        /** ~30 s of connecting before the pump gives up (the stack was never there). */
        const val RETRIES = 6
        const val RETRY_MS = 5_000L
    }
}
