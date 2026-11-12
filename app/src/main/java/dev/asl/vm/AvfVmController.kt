package dev.asl.vm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.asl.core.AGENT_VSOCK_PORT
import dev.asl.core.AgentConnection
import dev.asl.core.Asl
import dev.asl.core.ForwardedPort
import dev.asl.core.SessionRequest
import dev.asl.core.TerminalSession
import dev.asl.core.VmController
import dev.asl.core.VmState
import dev.asl.core.VmStatus
import dev.asl.core.distroDevice
import dev.asl.core.requireOk
import dev.asl.net.PortForwarder
import dev.asl.share.NinepServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class AvfVmController(context: Context) : VmController {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val forwarder = PortForwarder(::connect)

    private val _status = MutableStateFlow(VmStatus(VmState.Stopped))
    private val _runningDistros = MutableStateFlow(emptySet<String>())
    private val _consoleLog = MutableStateFlow("")
    override val status: StateFlow<VmStatus> = _status.asStateFlow()
    override val forwardedPorts: StateFlow<List<ForwardedPort>> = forwarder.ports
    override val runningDistros: StateFlow<Set<String>> = _runningDistros.asStateFlow()
    override val consoleLog: StateFlow<String> = _consoleLog.asStateFlow()

    @Volatile private var vm: Any? = null
    @Volatile private var control: Control? = null
    @Volatile private var stopRequested = false
    @Volatile private var bootStartedAt = 0L
    private var boot: Deferred<Unit>? = null

    init {
        scope.launch {
            Asl.settings.settings.map { it.asl.localhostForwarding to it.asl.bindAllInterfaces }
                .distinctUntilChanged().drop(1).collect { forwarder.update() }
        }
    }

    override suspend fun ensureRunning() {
        val job = mutex.withLock {
            boot?.takeIf { !it.isCompleted || vm != null } ?: scope.async { start() }.also { boot = it }
        }
        try {
            job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("VM start was canceled", e)
        }
    }

    override suspend fun shutdown() {
        val job = mutex.withLock { boot.also { boot = null } }
        stopRequested = true
        if (job?.isCompleted == false) job.cancelAndJoin()
        val machine = vm ?: return
        _status.value = VmStatus(VmState.Stopping)
        runCatching { control?.request("shutdown") }
        if (withTimeoutOrNull(15_000) { _status.first { it.state != VmState.Stopping } } == null) {
            runCatching { Avf.call(machine, "stop") }
            onVmStopped(machine, null)
        }
    }

    override suspend fun restart() {
        shutdown()
        ensureRunning()
    }

    override suspend fun terminate(distroId: String) {
        control?.request("terminate", buildJsonObject { put("distro", distroId) })
    }

    override suspend fun setDefaultUser(distroId: String, user: String) {
        ensureRunning()
        val channel = control ?: throw IOException("The virtual machine is not running")
        channel.request("setDefaultUser", buildJsonObject { put("distro", distroId); put("user", user) })
        Asl.distros.update(distroId) { it.copy(defaultUser = user) }
    }

    override suspend fun openSession(request: SessionRequest): TerminalSession {
        ensureRunning()
        val distros = Asl.distros.distros.value
        val distro = request.distroId?.let { id -> distros.firstOrNull { it.id == id } ?: throw IOException("Unknown distro $id") }
            ?: distros.firstOrNull { it.isDefault } ?: distros.firstOrNull() ?: throw IOException("No distro is installed")
        val header = buildJsonObject {
            put("svc", "session")
            put("distro", distro.id)
            // Omitted unless the profile names one, so the distro's /etc/wsl.conf [user] default wins, as in WSL.
            request.user?.let { put("user", it) }
            if (distro.oobePending) put("oobe", true)
            put("cwd", request.cwd ?: "~")
            putJsonArray("argv") { request.argv.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("env") { request.env.forEach { (k, v) -> put(k, v) } }
            put("cols", request.cols)
            put("rows", request.rows)
            put("tty", true)
        }
        return AgentSession(openChannel(header), scope)
    }

    override suspend fun openChannel(header: JsonObject): AgentConnection {
        ensureRunning()
        return withContext(Dispatchers.IO) { connect(header) }
    }

    private fun connect(header: JsonObject): VsockConnection {
        val machine = vm ?: throw IOException("VM is not running")
        return VsockConnection(Avf.call(machine, "connectVsock", AGENT_VSOCK_PORT) as ParcelFileDescriptor, header)
    }

    private suspend fun start() {
        stopRequested = false
        bootStartedAt = System.currentTimeMillis()
        _status.value = VmStatus(VmState.Starting, "Preparing VM images")
        try {
            val settings = Asl.settings.settings.value.asl
            val distros = Asl.distros.distros.value
            val dir = File(context.filesDir, "vm").apply { mkdirs() }
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val stamp = File(dir, "version")
            val version = "${info.longVersionCode}:${info.lastUpdateTime}"
            if (!stamp.exists() || stamp.readText() != version) {
                for (name in IMAGES) {
                    try {
                        context.assets.open("vm/$name").use { src -> File(dir, name).outputStream().use { src.copyTo(it) } }
                    } catch (e: FileNotFoundException) {
                        throw IOException("VM asset vm/$name is missing from the APK. Run guest/build.sh, then rebuild the app.", e)
                    }
                }
                stamp.writeText(version)
            }

            _consoleLog.value = ""
            val machine = Avf.createVm(
                context, VM_NAME, settings.memoryMb, settings.matchHostCpus,
                File(dir, IMAGES[0]).path, File(dir, IMAGES[1]).path,
                listOfNotNull(
                    "console=hvc0 rdinit=/init quiet asl.port=$AGENT_VSOCK_PORT arm64.nompam asl.time=${System.currentTimeMillis() / 1000}",
                    // MATCH_HOST clones big.LITTLE topology, which resets Tensor G3/G4 guests with many cores.
                    "nr_cpus=${minOf(Runtime.getRuntime().availableProcessors(), MAX_CPUS)}"
                        .takeIf { settings.matchHostCpus && "nr_cpus=" !in settings.kernelCommandLine },
                    settings.kernelCommandLine,
                ).joinToString(" "),
                distros.map { it.diskPath },
            )
            Avf.setCallback(machine) { error -> onVmStopped(machine, error) }
            vm = machine
            Avf.call(machine, "run")
            for (getter in listOf("getConsoleOutput", "getLogOutput")) {
                thread(isDaemon = true, name = "asl-$getter") {
                    runCatching {
                        (Avf.call(machine, getter) as InputStream).reader().use { reader ->
                            val buf = CharArray(4096)
                            while (true) {
                                val n = reader.read(buf)
                                if (n < 0) break
                                _consoleLog.update { (it + String(buf, 0, n)).takeLast(CONSOLE_LIMIT) }
                            }
                        }
                    }
                }
            }
            runCatching { context.startForegroundService(Intent(context, VmService::class.java)) }
                .onFailure { Log.w(TAG, "Cannot start VmService", it) }

            _status.value = VmStatus(VmState.Starting, "Waiting for the guest agent")
            val conn = withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                var attempt: VsockConnection? = null
                while (attempt == null) {
                    if (vm !== machine) throw IOException(_status.value.message ?: "VM stopped during boot")
                    attempt = runCatching { connect(buildJsonObject { put("svc", "control") }) }.getOrNull()
                    if (attempt == null) delay(500)
                }
                attempt
            } ?: throw IOException("The guest agent did not answer within ${AGENT_TIMEOUT_MS / 1000} s; see the console log")
            val ctl = Control(conn)
            control = ctl
            ctl.request("hello")
            val androidMount = ANDROID_MOUNT.takeIf { settings.mountAndroidStorage && Environment.isExternalStorageManager() }
            ctl.request("configure", buildJsonObject {
                putJsonArray("distros") {
                    distros.forEachIndexed { i, d ->
                        addJsonObject {
                            put("id", d.id)
                            put("name", d.name)
                            put("device", distroDevice(i))
                            put("defaultUser", d.defaultUser)
                            put("systemd", d.systemd)
                            put("hostname", d.hostname)
                        }
                    }
                }
                put("androidMount", androidMount)
            })
            _status.value = VmStatus(VmState.Running)

            if (androidMount != null) {
                thread(isDaemon = true, name = "asl-9p") {
                    runCatching {
                        connect(buildJsonObject { put("svc", "share9p"); put("tag", "android"); put("mountpoint", androidMount) })
                            .use { NinepServer(settings.androidStoragePath, it).serve() }
                    }.onFailure { Log.w(TAG, "Android storage share ended", it) }
                }
            }
        } catch (e: Throwable) {
            val machine = vm
            machine?.let { runCatching { Avf.call(it, "stop") } }
            onVmStopped(machine, if (e is CancellationException) null else e.message ?: e.toString())
            throw e
        }
    }

    /** Tears down per-boot state; ignores callbacks from a VM instance that is no longer current. */
    @Synchronized
    private fun onVmStopped(machine: Any?, error: String?) {
        if (machine != null && vm !== machine) return
        vm = null
        control?.close()
        control = null
        forwarder.update(emptyList())
        _runningDistros.value = emptySet()
        // A failed boot reports twice (callback, then start()'s catch); diagnose only the first.
        if (error != null && !stopRequested && _status.value.state != VmState.Error) scope.launch { appendFailureReport(error) }
        _status.value = if (error == null || stopRequested) VmStatus(VmState.Stopped) else VmStatus(VmState.Error, error)
    }

    private suspend fun appendFailureReport(error: String) = withContext(Dispatchers.IO) {
        delay(HOST_LOG_SETTLE_MS)
        val since = bootStartedAt
        // crosvm and virtmgr lines from this boot, or null without READ_LOGS.
        val lines = if (context.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) null else runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-T", "%d.%03d".format(since / 1000, since % 1000), "-s", "crosvm", "virtmgr")
                .redirectErrorStream(true).start()
            // Android may hold the read until the user answers its log-access prompt.
            val timeout = launch { delay(HOST_LOG_TIMEOUT_MS); process.destroy() }
            try {
                process.inputStream.bufferedReader().readLines().filterNot { it.startsWith("---------") }.takeLast(HOST_LOG_LINES)
            } finally {
                timeout.cancel()
            }
        }.getOrElse { listOf("logcat failed: ${it.message}") }
        val report = buildString {
            append("\n[asl] $error\n")
            when {
                lines == null -> append("[asl] Grant READ_LOGS so ASL can show why crosvm stopped:\n${context.vmGrantCommands(listOf(Manifest.permission.READ_LOGS))}\n")
                lines.isEmpty() -> append("[asl] No crosvm log lines were readable; allow ASL to access device logs when Android asks.\n")
                else -> lines.forEach { append("[host] $it\n") }
            }
            StartFailureHints.entries.firstOrNull { (pattern, _) -> lines.orEmpty().any { pattern in it } }
                ?.let { append("[asl] ${it.value}\n") }
        }
        _consoleLog.update { (it + report).takeLast(CONSOLE_LIMIT) }
    }

    /** The `control` service: id-correlated requests plus pushed events. */
    private inner class Control(private val conn: VsockConnection) : AutoCloseable {
        private val ids = AtomicLong()
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
        private val writer = conn.output.bufferedWriter()

        init {
            scope.launch {
                runCatching {
                    conn.input.bufferedReader().forEachLine { line ->
                        val msg = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
                        val id = msg["id"]?.jsonPrimitive?.longOrNull
                        if (id != null) {
                            pending[id]?.let { reply ->
                                runCatching { msg.requireOk() }
                                    .onSuccess { reply.complete(it["result"] ?: JsonNull) }
                                    .onFailure { reply.completeExceptionally(it) }
                            }
                            return@forEachLine
                        }
                        when (msg["event"]?.jsonPrimitive?.contentOrNull) {
                            "ports" -> forwarder.update(msg["ports"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty())
                            "distro" -> {
                                val distro = msg["distro"]?.jsonPrimitive?.contentOrNull ?: return@forEachLine
                                val running = msg["state"]?.jsonPrimitive?.contentOrNull == "running"
                                _runningDistros.update { if (running) it + distro else it - distro }
                                // The guest resolves /etc/wsl.conf, so the user it reports is the one a login gets.
                                val user = msg["user"]?.jsonPrimitive?.contentOrNull
                                if (user != null && Asl.distros.distros.value.any { it.id == distro && it.defaultUser != user }) {
                                    scope.launch { Asl.distros.update(distro) { it.copy(defaultUser = user) } }
                                }
                            }
                            "oobe" -> {
                                val distro = msg["distro"]?.jsonPrimitive?.contentOrNull ?: return@forEachLine
                                val user = msg["user"]?.jsonPrimitive?.contentOrNull
                                scope.launch { Asl.distros.update(distro) { it.copy(oobePending = false, defaultUser = user ?: it.defaultUser) } }
                            }
                        }
                    }
                }
                pending.values.forEach { it.completeExceptionally(IOException("Control channel closed")) }
            }
        }

        suspend fun request(op: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
            val id = ids.incrementAndGet()
            val reply = CompletableDeferred<JsonElement>().also { pending[id] = it }
            try {
                val line = JsonObject(args + mapOf("id" to JsonPrimitive(id), "op" to JsonPrimitive(op))).toString()
                withContext(Dispatchers.IO) {
                    synchronized(writer) {
                        writer.write(line)
                        writer.write("\n")
                        writer.flush()
                    }
                }
                return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { reply.await() } ?: throw IOException("Agent did not answer '$op'")
            } finally {
                pending.remove(id)
            }
        }

        override fun close() = conn.close()
    }

    private companion object {
        const val TAG = "AvfVmController"
        const val VM_NAME = "asl"
        const val ANDROID_MOUNT = "/mnt/android"
        const val CONSOLE_LIMIT = 64 * 1024
        const val AGENT_TIMEOUT_MS = 90_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val MAX_CPUS = 4
        const val HOST_LOG_SETTLE_MS = 500L
        const val HOST_LOG_TIMEOUT_MS = 10_000L
        const val HOST_LOG_LINES = 40
        val IMAGES = listOf("Image", "initrd.img")
        val StartFailureHints = mapOf(
            "Bad address" to "The hypervisor refused the guest's memory size, which GenieZone does after long uptime. " +
                "Lower Memory in Settings → Virtual machine, or reboot the device.",
            "Cannot allocate memory" to "The host ran out of memory for the guest. Close other apps or lower Memory in Settings → Virtual machine.",
        )
    }
}
