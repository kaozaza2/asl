package dev.asl.distro

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import dev.asl.core.Asl
import dev.asl.core.CatalogEntry
import dev.asl.core.Distro
import dev.asl.core.DISTRO_NAME
import dev.asl.core.DistroManager
import dev.asl.core.Progress
import dev.asl.core.VmState
import dev.asl.core.distroDevice
import dev.asl.core.openAgentChannel
import dev.asl.core.requireOk
import dev.asl.settings.SettingsJson
import dev.asl.settings.distroProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream

class DistroRepository(private val context: Context) : DistroManager {
    private val registry = AtomicFile(File(context.filesDir, "distros.json"))
    private val disks = File(context.filesDir, "disks").apply { mkdirs() }
    private val state = MutableStateFlow(
        try {
            SettingsJson.decodeFromString<List<Distro>>(registry.readFully().decodeToString())
        } catch (_: FileNotFoundException) {
            emptyList()
        }
    )
    override val distros: StateFlow<List<Distro>> = state.asStateFlow()

    init {
        // A pendingImport record surviving a restart is an import the process died in.
        val stale = state.value.filter { it.pendingImport }
        if (stale.isNotEmpty()) {
            stale.forEach { File(it.diskPath).delete() }
            commit { list -> list - stale.toSet() }
        }
    }

    @Synchronized
    private fun commit(transform: (List<Distro>) -> List<Distro>): List<Distro> {
        val next = transform(state.value).let { list ->
            if (list.isEmpty() || list.any { it.isDefault }) list
            else list.mapIndexed { i, d -> if (i == 0) d.copy(isDefault = true) else d }
        }
        val out = registry.startWrite()
        try {
            out.write(SettingsJson.encodeToString(next).toByteArray())
            registry.finishWrite(out)
        } catch (e: IOException) {
            registry.failWrite(out)
            throw e
        }
        state.value = next
        return next
    }

    private fun find(distroId: String) =
        state.value.firstOrNull { it.id == distroId } ?: throw IllegalArgumentException("No distribution with id $distroId")

    private fun requireNewName(name: String) {
        require(DISTRO_NAME.matches(name)) { "Invalid distribution name '$name'" }
        require(state.value.none { it.name.equals(name, ignoreCase = true) }) { "A distribution named '$name' already exists" }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, stage: String, total: Long, onProgress: (Progress) -> Unit) {
        val buffer = ByteArray(1 shl 16)
        var done = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            onProgress(Progress(stage, done, total))
        }
        output.flush()
    }

    override suspend fun catalog(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        SettingsJson.decodeFromString(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
    }

    override suspend fun install(entry: CatalogEntry, name: String, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        requireNewName(name)
        val download = File.createTempFile("rootfs-", ".download", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val http = URL(entry.url).openConnection() as HttpURLConnection
            try {
                http.connectTimeout = 30_000
                http.readTimeout = 60_000
                if (http.responseCode !in 200..299) throw IOException("HTTP ${http.responseCode} fetching ${entry.url}")
                DigestInputStream(http.inputStream, digest).use { input ->
                    download.outputStream().use { copy(input, it, "download", http.contentLengthLong, onProgress) }
                }
            } finally {
                http.disconnect()
            }
            entry.sha256?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) throw IOException("sha256 mismatch for ${entry.url}: $actual")
            }
            importTarball(name, Uri.fromFile(download), entry.id, onProgress)
        } finally {
            download.delete()
        }
    }

    override suspend fun import(name: String, tarball: Uri, onProgress: (Progress) -> Unit) =
        importTarball(name, tarball, tarball.lastPathSegment, onProgress)

    private suspend fun importTarball(name: String, tarball: Uri, source: String?, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        requireNewName(name)
        val afd = context.contentResolver.openAssetFileDescriptor(tarball, "r") ?: throw FileNotFoundException(tarball.toString())
        afd.use {
            val input = BufferedInputStream(afd.createInputStream(), 1 shl 16)
            input.mark(16)
            val head = input.readNBytes(6)
            input.reset()
            val compression = MAGIC.entries.firstOrNull { (_, m) ->
                m.size <= head.size && m.indices.all { (head[it].toInt() and 0xff) == m[it] }
            }?.key ?: "none"

            val id = UUID.randomUUID().toString()
            val disk = File(disks, "$id.img")
            val distro = Distro(id = id, name = name, diskPath = disk.absolutePath, source = source,
                createdAt = System.currentTimeMillis(), pendingImport = true, oobePending = true)
            try {
                onProgress(Progress("prepare"))
                RandomAccessFile(disk, "rw").use { it.setLength(Asl.settings.settings.value.asl.defaultDiskSizeGb.toLong() shl 30) }
                val index = commit { it + distro }.indexOfFirst { it.id == id }
                Asl.vm.restart()
                val header = buildJsonObject {
                    put("svc", "import")
                    put("distro", id)
                    put("device", distroDevice(index))
                    put("compression", compression)
                }
                Asl.vm.openAgentChannel(header).use { conn ->
                    copy(input, conn.output, "import", afd.length, onProgress)
                    conn.shutdownOutput()
                    onProgress(Progress("finalize"))
                    val status = conn.input.bufferedReader().readLine() ?: throw IOException("Agent closed the import stream without a status")
                    SettingsJson.parseToJsonElement(status).jsonObject.requireOk()
                }
                commit { list -> list.map { if (it.id == id) it.copy(pendingImport = false) else it } }
            } catch (e: Throwable) {
                disk.delete()
                commit { list -> list.filterNot { it.id == id } }
                throw e
            }
            Unit
        }
    }

    override suspend fun export(distroId: String, target: Uri, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        require(!find(distroId).pendingImport) { "Distribution is still being imported" }
        val resolver = context.contentResolver
        val targetName = resolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: target.lastPathSegment.orEmpty()
        Asl.vm.openAgentChannel(buildJsonObject { put("svc", "export"); put("distro", distroId) }).use { conn ->
            val raw = resolver.openOutputStream(target, "wt") ?: throw FileNotFoundException(target.toString())
            (if (targetName.endsWith(".gz")) GZIPOutputStream(raw, 1 shl 16) else raw).use { out ->
                copy(conn.input, out, "export", -1, onProgress)
            }
        }
    }

    override suspend fun unregister(distroId: String, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val distro = find(distroId)
        val vmWasRunning = Asl.vm.status.value.state == VmState.Running
        onProgress(Progress("stopping"))
        if (distroId in Asl.vm.runningDistros.value) Asl.vm.terminate(distroId)
        // Delete the profile the WSL generator created for this distro rather than leaving it hidden.
        val generated = distroProfile(distro).guid
        Asl.settings.update { s ->
            val profiles = s.profiles.copy(list = s.profiles.list.filterNot { it.guid == generated })
            if (s.defaultProfile == generated) s.copy(defaultProfile = null, profiles = profiles) else s.copy(profiles = profiles)
        }
        commit { list -> list.filterNot { it.id == distroId } }
        onProgress(Progress("deleting"))
        File(distro.diskPath).delete()
        // Device letters follow registry order, so the remaining disks must be reattached.
        if (vmWasRunning) {
            onProgress(Progress("restarting"))
            Asl.vm.restart()
        }
        Unit
    }

    override suspend fun setDefault(distroId: String) = update(distroId) { it.copy(isDefault = true) }

    override suspend fun update(distroId: String, transform: (Distro) -> Distro) = withContext(Dispatchers.IO) {
        val old = find(distroId)
        val new = transform(old).copy(id = old.id, diskPath = old.diskPath)
        require(new.name == old.name || state.value.none { it.id != distroId && it.name.equals(new.name, ignoreCase = true) }) {
            "A distribution named '${new.name}' already exists"
        }
        require(DISTRO_NAME.matches(new.name)) { "Invalid distribution name '${new.name}'" }
        commit { list ->
            list.map {
                when {
                    it.id == distroId -> new
                    new.isDefault && !old.isDefault -> it.copy(isDefault = false)
                    else -> it
                }
            }
        }
        Unit
    }

    private companion object {
        val MAGIC = mapOf(
            "gz" to intArrayOf(0x1f, 0x8b),
            "xz" to intArrayOf(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00),
            "zst" to intArrayOf(0x28, 0xb5, 0x2f, 0xfd),
        )
    }
}
