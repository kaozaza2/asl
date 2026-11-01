package dev.asl.share

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.EACCES
import android.system.OsConstants.EBADF
import android.system.OsConstants.EINVAL
import android.system.OsConstants.EIO
import android.system.OsConstants.ENOENT
import android.system.OsConstants.EOPNOTSUPP
import android.system.OsConstants.O_ACCMODE
import android.system.OsConstants.O_APPEND
import android.system.OsConstants.O_CREAT
import android.system.OsConstants.O_EXCL
import android.system.OsConstants.O_TRUNC
import android.system.OsConstants.O_WRONLY
import android.system.OsConstants.S_IFDIR
import android.system.OsConstants.S_IFLNK
import android.system.OsConstants.S_IFMT
import android.system.OsConstants.S_ISDIR
import android.system.StructStat
import dev.asl.core.AgentConnection
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A sequential 9P2000.L server exposing [root] over an agent `share9p` connection. */
class NinepServer(root: String, private val conn: AgentConnection) {
    private val root = File(root).canonicalPath
    private val fids = HashMap<Int, Fid>()
    private var msize = MAX_MSIZE

    private class Fid(var path: String, val uid: Int) {
        var fd: FileDescriptor? = null
        var entries: List<String> = emptyList()
    }

    fun serve() {
        val input = DataInputStream(BufferedInputStream(conn.input, 1 shl 16))
        val size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteBuffer.allocate(MAX_MSIZE).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (true) {
                input.readFully(size.array())
                val req = ByteBuffer.wrap(ByteArray(size.getInt(0) - 4).also { input.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
                val type = req.get().toInt() and 0xff
                val tag = req.short
                out.clear()
                out.position(HEADER)
                val rtype = try {
                    handle(type, req, out)
                    type + 1
                } catch (e: Exception) {
                    out.clear()
                    out.position(HEADER)
                    out.putInt(
                        when (e) {
                            is ErrnoException -> e.errno
                            is FileNotFoundException -> ENOENT
                            is SecurityException -> EACCES
                            else -> EIO
                        },
                    )
                    RLERROR
                }
                out.putInt(0, out.position()).put(4, rtype.toByte()).putShort(5, tag)
                conn.output.write(out.array(), 0, out.position())
            }
        } catch (eof: EOFException) {
        } finally {
            fids.values.forEach { f -> f.fd?.let { runCatching { Os.close(it) } } }
        }
    }

    private fun handle(type: Int, req: ByteBuffer, out: ByteBuffer) {
        when (type) {
            100 -> { // Tversion
                msize = minOf(req.int, MAX_MSIZE)
                val version = req.str()
                fids.values.forEach { f -> f.fd?.let { runCatching { Os.close(it) } } }
                fids.clear()
                out.putInt(msize).putStr(if (version.startsWith("9P2000.L")) "9P2000.L" else "unknown")
            }
            104 -> { // Tattach
                val fid = req.int
                req.int
                req.str()
                req.str()
                val uid = req.int
                fids[fid] = Fid(root, if (uid == -1) 0 else uid)
                out.putQid(Os.stat(root))
            }
            110 -> { // Twalk
                val fidNo = req.int
                val fid = fid(fidNo)
                val newfid = req.int
                val names = List(req.short.toInt() and 0xffff) { req.str() }
                var path = fid.path
                val stats = ArrayList<StructStat>()
                for (name in names) {
                    val next = if (name == "..") parentOf(path) else child(path, name)
                    val st = try {
                        Os.lstat(next)
                    } catch (e: ErrnoException) {
                        if (stats.isEmpty()) throw e else break
                    }
                    path = next
                    stats += st
                }
                if (stats.size == names.size) {
                    if (newfid == fidNo) fid.path = path else fids[newfid] = Fid(path, fid.uid)
                }
                out.putShort(stats.size.toShort())
                stats.forEach { out.putQid(it) }
            }
            24 -> { // Tgetattr
                val fid = fid(req.int)
                val st = Os.lstat(fid.path)
                out.putLong(0x7ff).putQid(st).putInt((st.st_mode and S_IFMT) or 0x1ff).putInt(fid.uid).putInt(fid.uid)
                    .putLong(st.st_nlink).putLong(st.st_rdev).putLong(st.st_size).putLong(st.st_blksize).putLong(st.st_blocks)
                listOf(st.st_atim, st.st_mtim, st.st_ctim).forEach { out.putLong(it.tv_sec).putLong(it.tv_nsec) }
                out.putLong(0).putLong(0).putLong(0).putLong(0) // btime, gen, data_version
            }
            26 -> { // Tsetattr
                val fid = fid(req.int)
                val valid = req.int
                val mode = req.int
                req.int
                req.int
                val size = req.long
                req.long
                req.long
                val mtime = req.long * 1000 + req.long / 1_000_000
                if (valid and 0x8 != 0) {
                    fid.fd?.let { Os.ftruncate(it, size) } ?: Os.open(fid.path, O_WRONLY, 0).let { fd ->
                        try { Os.ftruncate(fd, size) } finally { Os.close(fd) }
                    }
                }
                if (valid and 0x1 != 0) runCatching { Os.chmod(fid.path, mode and 0xfff) }
                if (valid and 0x20 != 0) File(fid.path).setLastModified(if (valid and 0x100 != 0) mtime else System.currentTimeMillis())
            }
            12 -> { // Tlopen
                val fid = fid(req.int)
                val flags = req.int
                val st = Os.stat(fid.path)
                if (!S_ISDIR(st.st_mode)) fid.fd = Os.open(fid.path, flags and OPEN_FLAGS, 0)
                out.putQid(st).putInt(0)
            }
            14 -> { // Tlcreate
                val fid = fid(req.int)
                val path = child(fid.path, req.str())
                val fd = Os.open(path, (req.int and OPEN_FLAGS) or O_CREAT, req.int and 0x1ff)
                fid.path = path
                fid.fd = fd
                out.putQid(Os.fstat(fd)).putInt(0)
            }
            72 -> { // Tmkdir
                val path = child(fid(req.int).path, req.str())
                Os.mkdir(path, req.int and 0x1ff)
                out.putQid(Os.lstat(path))
            }
            76 -> Os.remove(child(fid(req.int).path, req.str())) // Tunlinkat
            74 -> move(child(fid(req.int).path, req.str()), child(fid(req.int).path, req.str())) // Trenameat
            20 -> move(fid(req.int).path, child(fid(req.int).path, req.str())) // Trename
            116 -> { // Tread
                val fd = fid(req.int).fd ?: throw ErrnoException("read", EBADF)
                val offset = req.long
                val count = minOf(req.int, msize - 11)
                val at = out.position()
                out.putInt(0)
                val n = Os.pread(fd, out.array(), out.position(), count, offset)
                out.putInt(at, n)
                out.position(out.position() + n)
            }
            118 -> { // Twrite
                val fd = fid(req.int).fd ?: throw ErrnoException("write", EBADF)
                val offset = req.long
                val count = req.int
                out.putInt(Os.pwrite(fd, req.array(), req.position(), count, offset))
            }
            40 -> { // Treaddir
                val fid = fid(req.int)
                val offset = req.long.toInt()
                val count = minOf(req.int, msize - 11)
                if (offset == 0) {
                    fid.entries = listOf(".", "..") + (File(fid.path).list()?.sorted() ?: throw ErrnoException("readdir", EACCES))
                }
                val at = out.position()
                out.putInt(0)
                for (i in offset until fid.entries.size) {
                    val name = fid.entries[i]
                    val bytes = name.toByteArray()
                    if (out.position() - at - 4 + 24 + bytes.size > count) break
                    val path = when (name) {
                        "." -> fid.path
                        ".." -> parentOf(fid.path)
                        else -> File(fid.path, name).path
                    }
                    val st = runCatching { Os.lstat(path) }.getOrNull() ?: continue
                    // Linux DT_* values are the S_IFMT bits shifted down.
                    out.putQid(st).putLong(i + 1L).put(((st.st_mode and S_IFMT) shr 12).toByte())
                        .putShort(bytes.size.toShort()).put(bytes)
                }
                out.putInt(at, out.position() - at - 4)
            }
            8 -> { // Tstatfs
                val s = Os.statvfs(fid(req.int).path)
                out.putInt(V9FS_MAGIC).putInt(s.f_bsize.toInt()).putLong(s.f_blocks).putLong(s.f_bfree).putLong(s.f_bavail)
                    .putLong(s.f_files).putLong(s.f_ffree).putLong(s.f_fsid).putInt(s.f_namemax.toInt())
            }
            50 -> fid(req.int).fd?.let { Os.fsync(it) } // Tfsync
            52 -> out.put(0.toByte()) // Tlock: always granted, the share is single-client
            54 -> { // Tgetlock: report unlocked
                req.int
                req.get()
                out.put(2.toByte()).putLong(req.long).putLong(req.long).putInt(req.int).putStr(req.str())
            }
            120 -> fids.remove(req.int)?.fd?.let { Os.close(it) } // Tclunk
            122 -> { // Tremove
                val fid = fids.remove(req.int) ?: throw ErrnoException("remove", EBADF)
                fid.fd?.let { Os.close(it) }
                Os.remove(fid.path)
            }
            108 -> Unit // Tflush: requests are served in order, nothing is pending
            else -> throw ErrnoException("9p", EOPNOTSUPP)
        }
    }

    private fun fid(n: Int) = fids[n] ?: throw ErrnoException("9p", EBADF)

    private fun parentOf(path: String) = if (path == root) root else File(path).parent ?: root

    private fun child(dir: String, name: String): String {
        if (name.isEmpty() || name == "." || name == ".." || '/' in name) throw ErrnoException("9p", EINVAL)
        val path = File(dir, name).path
        val real = File(path).canonicalPath
        if (real != root && !real.startsWith(root.trimEnd('/') + "/")) throw ErrnoException("9p", EACCES)
        return path
    }

    private fun move(from: String, to: String) {
        Os.rename(from, to)
        fids.values.filter { it.path == from || it.path.startsWith("$from/") }.forEach { it.path = to + it.path.removePrefix(from) }
    }

    private fun ByteBuffer.str(): String = ByteArray(short.toInt() and 0xffff).also { get(it) }.toString(Charsets.UTF_8)

    private fun ByteBuffer.putStr(s: String): ByteBuffer = s.toByteArray().let { putShort(it.size.toShort()).put(it) }

    private fun ByteBuffer.putQid(st: StructStat): ByteBuffer {
        val type = when (st.st_mode and S_IFMT) {
            S_IFDIR -> 0x80
            S_IFLNK -> 0x02
            else -> 0
        }
        return put(type.toByte()).putInt(0).putLong(st.st_ino)
    }

    private companion object {
        // Must stay >= the guest's mount option msize=262144 (guest/agent/share.go); +4096 for the header.
        const val MAX_MSIZE = 262144 + 4096
        const val HEADER = 7
        const val RLERROR = 7
        const val V9FS_MAGIC = 0x01021997
        val OPEN_FLAGS = O_ACCMODE or O_CREAT or O_EXCL or O_TRUNC or O_APPEND
    }
}
