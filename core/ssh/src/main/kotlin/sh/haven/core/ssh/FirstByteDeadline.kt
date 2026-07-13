package sh.haven.core.ssh

import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guards the wait for the FIRST byte from a stream that has no socket behind it.
 *
 * A proxied SSH session gets its I/O from a tunnelling channel, not a socket
 * (`ProxyJump.getSocket()` is null, as JSch's `Proxy` contract allows), so JSch
 * can't apply a read timeout: a target that never speaks blocks the KEX read
 * forever, with no error and — because the connection log is only written once
 * connect returns or throws — no trace anywhere (#383).
 *
 * If nothing arrives within the deadline, [onDeadline] runs; tearing the channel
 * down from there unblocks the pending read and turns an eternal spinner into an
 * ordinary connect failure.
 *
 * Only the first byte is guarded, deliberately. Everything slow but legitimate —
 * typing a password, a TOTP code, a FIDO touch — happens *after* the server has
 * spoken, and must never be cut off by this.
 */
internal class FirstByteDeadlineInputStream(
    private val delegate: InputStream,
    deadlineMs: Long,
    private val onDeadline: () -> Unit,
) : InputStream() {

    private val settled = AtomicBoolean(false)

    /** True once the deadline expired with the stream still silent. */
    @Volatile
    var expired: Boolean = false
        private set

    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ssh-first-byte-deadline").apply { isDaemon = true }
    }

    init {
        timer.schedule({
            if (settled.compareAndSet(false, true)) {
                expired = true
                onDeadline()
            }
        }, deadlineMs, TimeUnit.MILLISECONDS)
    }

    /** The stream spoke (or ended) — disarm; the rest of the handshake is untimed. */
    private fun disarm() {
        if (settled.compareAndSet(false, true)) timer.shutdownNow()
    }

    override fun read(): Int = delegate.read().also { disarm() }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { disarm() }

    override fun available(): Int = delegate.available()

    override fun close() {
        timer.shutdownNow()
        delegate.close()
    }
}
