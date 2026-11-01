package dev.asl.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import dev.asl.R
import dev.asl.core.AgentConnection
import dev.asl.core.Asl
import dev.asl.distro.openAgentChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer

class DistroDocumentsProvider : DocumentsProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(): Boolean {
        // Providers are created before Application.onCreate populates Asl, so subscribe once the main looper runs.
        Handler(Looper.getMainLooper()).post {
            scope.launch {
                combine(Asl.distros.distros, Asl.vm.runningDistros) { _, _ -> }.collect {
                    requireContext().contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
                }
            }
        }
        return true
    }

    private fun connect(header: JsonObject): AgentConnection = try {
        runBlocking(Dispatchers.IO) { withTimeout(TIMEOUT_MS) { openAgentChannel(header) } }
    } catch (e: Exception) {
        throw FileNotFoundException(e.message ?: e.toString()).apply { initCause(e) }
    }

    private fun fs(documentId: String, op: String, to: String? = null): JsonObject = connect(buildJsonObject {
        put("svc", "fs")
        put("distro", documentId.substringBefore(':'))
        put("op", op)
        put("path", documentId.substringAfter(':'))
        if (to != null) put("to", to)
        if (op == "remove") put("recursive", true)
    }).use { it.reply }

    private fun notifyChildren(documentId: String) {
        val parent = File(documentId.substringAfter(':')).parent ?: "/"
        requireContext().contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, "${documentId.substringBefore(':')}:$parent"), null)
    }

    private fun addRow(cursor: MatrixCursor, documentId: String, entry: JsonObject) {
        val path = documentId.substringAfter(':')
        val isDir = entry["type"]?.jsonPrimitive?.contentOrNull == "dir"
        val name = File(path).name
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE,
                if (isDir) Document.MIME_TYPE_DIR
                else MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
            add(Document.COLUMN_SIZE, if (isDir) null else entry["size"]?.jsonPrimitive?.longOrNull)
            add(Document.COLUMN_LAST_MODIFIED, entry["mtime"]?.jsonPrimitive?.longOrNull?.times(1000))
            add(Document.COLUMN_FLAGS, when {
                path == "/" -> Document.FLAG_DIR_SUPPORTS_CREATE
                isDir -> Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
                else -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            })
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val running = Asl.vm.runningDistros.value
        Asl.distros.distros.value.filterNot { it.pendingImport }.forEach { distro ->
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, distro.id)
                add(Root.COLUMN_DOCUMENT_ID, "${distro.id}:/")
                add(Root.COLUMN_TITLE, "ASL: ${distro.name}")
                add(Root.COLUMN_SUMMARY, if (distro.id in running) "Running" else "Stopped")
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            }
        }
        cursor.setNotificationUri(requireContext().contentResolver, DocumentsContract.buildRootsUri(AUTHORITY))
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        // The root row is synthesized so browsing the roots list never boots the VM.
        val entry = if (documentId.substringAfter(':') == "/") buildJsonObject { put("type", "dir") }
        else fs(documentId, "stat")["entry"]?.jsonObject ?: throw FileNotFoundException(documentId)
        addRow(cursor, documentId, entry)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val distroId = parentDocumentId.substringBefore(':')
        val parent = parentDocumentId.substringAfter(':')
        try {
            fs(parentDocumentId, "list")["entries"]?.jsonArray?.forEach {
                val entry = it.jsonObject
                addRow(cursor, "$distroId:${File(parent, entry["name"]!!.jsonPrimitive.content).path}", entry)
            }
        } catch (e: Exception) {
            cursor.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, e.message ?: e.toString()) }
        }
        cursor.setNotificationUri(requireContext().contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val flags = ParcelFileDescriptor.parseMode(mode)
        val access = flags and ParcelFileDescriptor.MODE_READ_WRITE
        val conn = connect(buildJsonObject {
            put("svc", "file")
            put("distro", documentId.substringBefore(':'))
            put("path", documentId.substringAfter(':'))
            put("mode", ACCESS_MODES.getValue(access))
            put("create", (flags and ParcelFileDescriptor.MODE_CREATE) != 0)
            put("truncate", (flags and ParcelFileDescriptor.MODE_TRUNCATE) != 0)
        })
        val thread = HandlerThread("asl-file").apply { start() }
        val callback = object : ProxyFileDescriptorCallback() {
            val out = DataOutputStream(conn.output.buffered())
            val input = DataInputStream(conn.input.buffered())
            val scratch = ByteArray(8)

            fun request(op: Int, offset: Long, length: Int, data: ByteArray? = null, into: ByteArray = scratch): Int = try {
                out.writeByte(op)
                out.writeLong(offset)
                out.writeInt(length)
                if (data != null) out.write(data, 0, length)
                out.flush()
                val result = input.readInt()
                input.readFully(into, 0, input.readInt())
                if (result < 0) throw ErrnoException("file", -result)
                result
            } catch (e: IOException) {
                throw ErrnoException("file", OsConstants.EIO, e)
            }

            override fun onGetSize(): Long {
                request(OP_SIZE, 0, 0)
                return ByteBuffer.wrap(scratch).long
            }

            override fun onRead(offset: Long, size: Int, data: ByteArray) = request(OP_READ, offset, size, into = data)

            override fun onWrite(offset: Long, size: Int, data: ByteArray) = request(OP_WRITE, offset, size, data)

            override fun onFsync() {
                request(OP_FSYNC, 0, 0)
            }

            override fun onRelease() {
                conn.close()
                thread.quitSafely()
                if (access != ParcelFileDescriptor.MODE_READ_ONLY) notifyChildren(documentId)
            }
        }
        return try {
            requireContext().getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(access, callback, Handler(thread.looper))
        } catch (e: IOException) {
            conn.close()
            thread.quitSafely()
            throw e
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val name = if (isDir || extension == null || displayName.endsWith(".$extension")) displayName else "$displayName.$extension"
        val distroId = parentDocumentId.substringBefore(':')
        val parent = parentDocumentId.substringAfter(':')
        val documentId = generateSequence(0) { it + 1 }.take(100)
            .map { i -> if (i == 0) name else "${name.substringBeforeLast('.')} ($i)${name.substring(name.substringBeforeLast('.').length)}" }
            .map { "$distroId:${File(parent, it).path}" }
            .firstOrNull { runCatching { fs(it, "stat") }.isFailure }
            ?: throw FileNotFoundException("No free name for $displayName in $parent")
        if (isDir) fs(documentId, "mkdir")
        else connect(buildJsonObject {
            put("svc", "file")
            put("distro", distroId)
            put("path", documentId.substringAfter(':'))
            put("mode", "w")
            put("create", true)
            put("truncate", false)
        }).close()
        notifyChildren(documentId)
        return documentId
    }

    override fun deleteDocument(documentId: String) {
        fs(documentId, "remove")
        notifyChildren(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val path = documentId.substringAfter(':')
        val to = File(File(path).parent ?: "/", displayName).path
        fs(documentId, "rename", to)
        notifyChildren(documentId)
        return "${documentId.substringBefore(':')}:$to"
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parentDocumentId.substringAfter(':').trimEnd('/')
        return parentDocumentId.substringBefore(':') == documentId.substringBefore(':') &&
            documentId.substringAfter(':').startsWith("$parent/")
    }

    private companion object {
        const val AUTHORITY = "dev.asl.documents"
        const val TIMEOUT_MS = 60_000L
        const val OP_READ = 0
        const val OP_WRITE = 1
        const val OP_SIZE = 2
        const val OP_FSYNC = 3
        val ACCESS_MODES = mapOf(
            ParcelFileDescriptor.MODE_READ_ONLY to "r",
            ParcelFileDescriptor.MODE_WRITE_ONLY to "w",
            ParcelFileDescriptor.MODE_READ_WRITE to "rw",
        )
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
