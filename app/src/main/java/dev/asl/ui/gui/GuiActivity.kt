package dev.asl.ui.gui

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.asl.R
import dev.asl.core.AgentConnection
import dev.asl.core.Asl
import dev.asl.core.openAgentChannel
import dev.asl.ui.terminal.VmPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * A GUI window, ASL's WSLg entry point: opened for profiles with `gui` instead of a terminal
 * tab. One connection renders the distro's X server desktop: the agent's `display` service
 * starts/attaches the server, then the RFB client pulls pixels over a `forward` stream. Closing
 * the window (or the stream) tears everything down.
 */
class GuiActivity : AppCompatActivity() {
    private lateinit var surface: GuiSurface
    private var client: RfbClient? = null
    private var pump: AudioPump? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = GuiSurface(this)
        setContentView(surface)
        surface.setBackgroundColor(Color.BLACK)
        title = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.app_name)
        lifecycleScope.launch(Dispatchers.IO) { start(intent.getStringExtra(EXTRA_DISTRO)) }
    }

    /** Runs on IO until the window ends: the RFB stream only ends when the server does. */
    private suspend fun CoroutineScope.start(distroId: String?) {
        val onScreen = { text: String -> runOnUiThread { surface.status = text } }
        val missing = VmPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        when {
            missing.isNotEmpty() -> onScreen(
                getString(R.string.main_vm_permission) + "\n" +
                    missing.joinToString("\n") { "adb shell pm grant $packageName $it" },
            )
            else -> try {
                Asl.vm.ensureRunning()
                val distros = Asl.distros.distros.value
                val distro = distroId?.let { id -> distros.firstOrNull { it.id == id } ?: throw IOException(getString(R.string.main_no_distro_name)) }
                    ?: distros.firstOrNull { it.isDefault } ?: distros.firstOrNull() ?: throw IOException(getString(R.string.main_no_distros))
                // Ports follow the registry order the guest already uses for disks and forwarding.
                val port = 5900 + distros.indexOfFirst { it.id == distro.id }
                // Sound alongside graphics, as far as the distro supports it; the pump degrades
                // to silence by itself when the distribution has no sound stack.
                if (Asl.settings.settings.value.asl.audio) {
                    pump = AudioPump { !isFinishing }.also { launch { it.run() } }
                }
                // Blocks until the RFB port is listening; the display can outlive windows.
                Asl.vm.openAgentChannel(buildJsonObject {
                    put("svc", "display"); put("distro", distro.id); put("port", port)
                }).use { }
                openClient(Asl.vm.openAgentChannel(buildJsonObject {
                    put("svc", "forward"); put("port", port)
                }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onScreen(e.message ?: e.toString())
            }
        }
    }

    private fun openClient(stream: AgentConnection) {
        val rfb = RfbClient(stream, object : RfbClient.Listener {
            override fun onScreen(width: Int, height: Int, name: String) {
                runOnUiThread { surface.onScreen(width, height) }
            }

            override fun onUpdate(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
                surface.onUpdate(x, y, width, height, pixels)
            }

            override fun onCopyRect(srcX: Int, srcY: Int, x: Int, y: Int, width: Int, height: Int) {
                surface.onCopyRect(srcX, srcY, x, y, width, height)
            }

            override fun onClosed(reason: String?) {
                // The window dies with its stream, as a window closes when its app exits.
                client = null
                runOnUiThread { finish() }
            }
        })
        client = rfb
        surface.client = rfb
        try {
            rfb.run()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Handshake failures return without updating the stream, so it stays open: close it.
            client?.close()
            runOnUiThread { surface.onClose(e.message ?: e.toString()) }
        }
    }

    override fun onDestroy() {
        client?.close()
        pump?.close()
        super.onDestroy()
    }

    companion object {
        /** The profile's distro (absent for the default) and the window title. */
        const val EXTRA_DISTRO = "dev.asl.gui.distro"
        const val EXTRA_NAME = "dev.asl.gui.name"
    }
}
