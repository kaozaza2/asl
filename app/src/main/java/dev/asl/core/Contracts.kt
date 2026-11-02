package dev.asl.core

import android.net.Uri
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.OutputStream

enum class VmState { Stopped, Starting, Running, Stopping, Error }

data class VmStatus(val state: VmState, val message: String? = null)

data class ForwardedPort(val port: Int, val bindAddress: String, val active: Int = 0)

data class SessionRequest(
    val distroId: String? = null,
    val user: String? = null,
    val cwd: String? = null,
    val argv: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cols: Int = 80,
    val rows: Int = 24,
)

interface TerminalSession {
    val output: Flow<ByteArray>
    val exitCode: Deferred<Int>
    fun write(data: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}

/** One vsock connection to the guest agent after the header handshake (docs/PROTOCOL.md). */
interface AgentConnection : AutoCloseable {
    val reply: JsonObject
    val input: InputStream
    val output: OutputStream
    val fd: java.io.FileDescriptor
    fun shutdownOutput()
}

interface VmController {
    val status: StateFlow<VmStatus>
    val forwardedPorts: StateFlow<List<ForwardedPort>>
    val runningDistros: StateFlow<Set<String>>
    val consoleLog: StateFlow<String>
    suspend fun ensureRunning()
    suspend fun shutdown()
    suspend fun restart()
    suspend fun terminate(distroId: String)
    /** Writes the distro's /etc/wsl.conf [user] default, which decides the login user, and records it. */
    suspend fun setDefaultUser(distroId: String, user: String)
    suspend fun openSession(request: SessionRequest): TerminalSession
    suspend fun openChannel(header: JsonObject): AgentConnection
}

interface DistroManager {
    val distros: StateFlow<List<Distro>>
    suspend fun catalog(): List<CatalogEntry>
    suspend fun install(entry: CatalogEntry, name: String, onProgress: (Progress) -> Unit)
    suspend fun import(name: String, tarball: Uri, onProgress: (Progress) -> Unit)
    suspend fun export(distroId: String, target: Uri, onProgress: (Progress) -> Unit)
    suspend fun unregister(distroId: String, onProgress: (Progress) -> Unit = {})
    suspend fun setDefault(distroId: String)
    suspend fun update(distroId: String, transform: (Distro) -> Distro)
}

data class Progress(val stage: String, val bytes: Long = 0, val total: Long = -1)

interface UsbPassthrough {
    val devices: StateFlow<List<UsbDeviceInfo>>
    fun refresh()
    fun attach(key: String)
    fun detach(key: String)
}

interface SettingsRepository {
    val settings: StateFlow<AslSettings>
    val file: java.io.File
    fun update(transform: (AslSettings) -> AslSettings)
}
