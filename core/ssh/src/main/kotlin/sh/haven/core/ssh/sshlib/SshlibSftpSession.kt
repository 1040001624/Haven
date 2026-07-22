package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import org.connectbot.sshlib.SshClient as SshlibClient
import sh.haven.core.ssh.SshIoException
import sh.haven.core.ssh.sftp.ListResult
import sh.haven.core.ssh.sftp.SftpAttrs
import sh.haven.core.ssh.sftp.SftpSession
import sh.haven.core.ssh.sftp.SftpWriteMode
import java.io.InputStream
import java.io.OutputStream

/**
 * [SftpSession] backed by sshlib (ssh-proto, #58).
 *
 * Unlike [sh.haven.core.ssh.sftp.JschSftpSession], this session OWNS its
 * whole SSH connection — sshlib SFTP cannot ride a JSch transport, so
 * [SshlibSftpConnector] dials a dedicated connection whose only channel is
 * this SFTP subsystem. [close] therefore tears down the connection too.
 *
 * sshlib's suspend surface returns [SftpResult] instead of throwing; every
 * result is unwrapped to a value or a [SshIoException] so callers see the
 * same error type as the JSch engine.
 */
internal class SshlibSftpSession(
    private val client: SshlibClient,
    private val sftp: SftpClient,
) : SftpSession {

    @Volatile
    private var cachedHome: String? = null

    override val isConnected: Boolean
        get() = sftp.isOpen

    override suspend fun list(path: String, onEntry: (SftpAttrs) -> ListResult) =
        withContext(Dispatchers.IO) {
            val handle = sftp.opendir(path).unwrap("opendir $path")
            try {
                outer@ while (true) {
                    val batch = sftp.readdir(handle).unwrap("readdir $path") ?: break
                    for (entry in batch) {
                        if (entry.filename == "." || entry.filename == "..") continue
                        val verdict = onEntry(entry.attrs.toSftpAttrs(entry.filename))
                        if (verdict == ListResult.BREAK) break@outer
                    }
                }
            } finally {
                sftp.close(handle)
            }
            Unit
        }

    override suspend fun stat(path: String): SftpAttrs = withContext(Dispatchers.IO) {
        sftp.stat(path).unwrap("stat $path")
            .toSftpAttrs(path.substringAfterLast('/').ifEmpty { path })
    }

    override suspend fun download(
        srcPath: String,
        output: OutputStream,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val total = sftp.stat(srcPath).unwrap("stat $srcPath").size ?: 0L
        val handle = sftp.open(srcPath, setOf(SftpOpenFlag.READ)).unwrap("open $srcPath")
        try {
            onBytes(0L, total)
            var offset = 0L
            while (true) {
                val chunk = sftp.read(handle, offset, CHUNK_BYTES).unwrap("read $srcPath") ?: break
                output.write(chunk)
                offset += chunk.size
                onBytes(offset, total)
            }
        } finally {
            sftp.close(handle)
        }
        Unit
    }

    override suspend fun upload(
        input: InputStream,
        sizeHint: Long,
        destPath: String,
        mode: SftpWriteMode,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val startOffset = when (mode) {
            SftpWriteMode.OVERWRITE -> 0L
            // Same semantics as JSch RESUME: continue at the destination's
            // current size, skipping the matching prefix of the input.
            SftpWriteMode.RESUME -> when (val r = sftp.stat(destPath)) {
                is SftpResult.Success -> r.value.size ?: 0L
                is SftpResult.ServerError ->
                    if (r.statusCode == SftpStatusCode.NO_SUCH_FILE) 0L
                    else throw SshIoException("stat $destPath: ${r.message}")
                is SftpResult.ProtocolError -> throw SshIoException("stat $destPath: ${r.message}")
                is SftpResult.IoError -> throw SshIoException("stat $destPath: ${r.cause.message}", r.cause)
            }
        }
        skipFully(input, startOffset)
        val flags = when (mode) {
            SftpWriteMode.OVERWRITE ->
                setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, SftpOpenFlag.TRUNCATE)
            SftpWriteMode.RESUME -> setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE)
        }
        val handle = sftp.open(destPath, flags).unwrap("open $destPath")
        try {
            onBytes(0L, sizeHint)
            var offset = startOffset
            var transferred = 0L
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val chunk = if (n == buffer.size) buffer else buffer.copyOf(n)
                sftp.write(handle, offset, chunk).unwrap("write $destPath")
                offset += n
                transferred += n
                onBytes(transferred, sizeHint)
            }
        } finally {
            sftp.close(handle)
        }
        Unit
    }

    override suspend fun home(): String = withContext(Dispatchers.IO) {
        cachedHome ?: sftp.realpath(".").unwrap("realpath .").also { cachedHome = it }
    }

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            val handle = sftp.open(path, setOf(SftpOpenFlag.READ)).unwrap("open $path")
            // Handle+offset reads are stateless, so unlike JSch's stream this
            // needs no pipe/thread workaround — each refill is one request.
            object : InputStream() {
                private var pos = offset
                private var buf = ByteArray(0)
                private var bufPos = 0
                private var eof = false
                private var closed = false

                private fun refill(): Boolean {
                    if (eof) return false
                    while (bufPos >= buf.size) {
                        val chunk = runBlocking { sftp.read(handle, pos, CHUNK_BYTES) }
                            .unwrap("read $path")
                        if (chunk == null) { eof = true; return false }
                        if (chunk.isEmpty()) continue
                        buf = chunk
                        bufPos = 0
                        pos += chunk.size
                    }
                    return true
                }

                override fun read(): Int =
                    if (!refill()) -1 else buf[bufPos++].toInt() and 0xFF

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    if (!refill()) return -1
                    val n = minOf(len, buf.size - bufPos)
                    System.arraycopy(buf, bufPos, b, off, n)
                    bufPos += n
                    return n
                }

                override fun available(): Int = buf.size - bufPos

                override fun close() {
                    if (closed) return
                    closed = true
                    runBlocking { sftp.close(handle) }
                }
            }
        }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        sftp.mkdir(path).unwrap("mkdir $path")
    }

    override suspend fun rmdir(path: String) = withContext(Dispatchers.IO) {
        sftp.rmdir(path).unwrap("rmdir $path")
    }

    override suspend fun rm(path: String) = withContext(Dispatchers.IO) {
        sftp.remove(path).unwrap("rm $path")
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        sftp.rename(from, to).unwrap("rename $from")
    }

    override suspend fun chmod(path: String, mode: Int) = withContext(Dispatchers.IO) {
        sftp.setstat(path, SftpAttributes(permissions = mode)).unwrap("chmod $path")
    }

    override fun close() {
        try { sftp.close() } catch (_: Exception) { /* best effort */ }
        try { runBlocking { client.disconnect() } } catch (_: Exception) { /* best effort */ }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) { remaining -= skipped; continue }
            // skip() may lawfully return 0 — fall back to reading
            if (input.read() < 0) return
            remaining--
        }
    }

    private fun <T> SftpResult<T>.unwrap(op: String): T = when (this) {
        is SftpResult.Success -> value
        is SftpResult.ServerError -> throw SshIoException("$op: $message ($statusCode)")
        is SftpResult.ProtocolError -> throw SshIoException("$op: $message")
        is SftpResult.IoError -> throw SshIoException("$op: ${cause.message}", cause)
    }

    private companion object {
        /** SFTP transfer chunk — under the common 32 KiB max-packet payload. */
        const val CHUNK_BYTES = 32 * 1024

        private fun SftpAttributes.toSftpAttrs(filename: String) = SftpAttrs(
            filename = filename,
            isDirectory = permissions?.let { it and S_IFMT == S_IFDIR } ?: false,
            isSymlink = permissions?.let { it and S_IFMT == S_IFLNK } ?: false,
            size = size ?: 0L,
            modifiedTimeSeconds = mtime ?: 0,
            permissions = permissions?.let { renderPermissions(it) } ?: "",
            uid = uid ?: 0,
            gid = gid ?: 0,
        )

        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val S_IFLNK = 0xA000

        /** Render mode bits like `drwxr-xr-x`, matching JSch's permissionsString. */
        private fun renderPermissions(mode: Int): String = buildString {
            append(
                when (mode and S_IFMT) {
                    S_IFDIR -> 'd'
                    S_IFLNK -> 'l'
                    else -> '-'
                },
            )
            for (shift in intArrayOf(6, 3, 0)) {
                val bits = (mode shr shift) and 0x7
                append(if (bits and 0x4 != 0) 'r' else '-')
                append(if (bits and 0x2 != 0) 'w' else '-')
                append(if (bits and 0x1 != 0) 'x' else '-')
            }
        }
    }
}
