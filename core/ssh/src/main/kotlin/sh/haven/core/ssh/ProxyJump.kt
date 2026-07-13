package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.Channel
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

private const val TAG = "ProxyJump"

/**
 * JSch [Proxy] implementation that tunnels through an existing SSH session
 * using a `direct-tcpip` channel, equivalent to `ssh -J` (ProxyJump).
 *
 * The jump host [Session] must already be connected before this proxy is used.
 */
class ProxyJump(private val jumpSession: Session) : Proxy {

    private var channel: Channel? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var deadline: FirstByteDeadlineInputStream? = null

    /** Target of the last [connect], for error messages. */
    private var target: String = "the target"

    /**
     * True when the channel opened but the target never sent a single byte
     * within the connect timeout. Callers turn this into a clear error instead
     * of the generic JSch failure the torn-down channel produces (#383).
     *
     * Sticky, and deliberately NOT cleared by [close]: JSch closes the proxy on
     * its way out of a failed `Session.connect()`, i.e. before the caller gets
     * to inspect why it failed. Clearing it there loses the very fact we need.
     */
    @Volatile
    var timedOut: Boolean = false
        private set

    override fun connect(factory: SocketFactory?, host: String, port: Int, timeout: Int) {
        Log.d(TAG, "Opening direct-tcpip channel to $host:$port (timeout=${timeout}ms, jumpConnected=${jumpSession.isConnected})")
        target = "$host:$port"
        val ch = jumpSession.getStreamForwarder(host, port)
        // Bind the streams BEFORE connecting: getInputStream() is what installs
        // JSch's server→client pipe, and Channel.write() swallows the NPE when
        // it isn't there yet — so the target's SSH banner, sent the moment the
        // channel opens, is silently dropped. JSch would then wait forever for
        // a banner that is never resent, and a proxied session has no socket for
        // the connect timeout to fire on. JSch says so itself: "getInputStream()
        // should be called before connect()". (#381)
        val raw = ch.inputStream
        output = ch.outputStream
        // The channel is not a socket, so JSch can't time out the read that
        // follows. Guard the wait for the target's first byte ourselves: nothing
        // by the deadline and we tear the channel down, which unblocks JSch and
        // surfaces a normal, logged connect failure rather than an eternal
        // spinner. Disarms as soon as the target speaks, so a slow interactive
        // auth later in the handshake is never cut off. (#383)
        deadline = FirstByteDeadlineInputStream(raw, timeout.toLong()) {
            timedOut = true
            Log.w(TAG, "No reply from $host:$port through the jump host after ${timeout}ms — closing the channel")
            try { ch.disconnect() } catch (_: Exception) { /* best effort — unblocking the read is the point */ }
        }
        input = deadline
        ch.connect(timeout)
        channel = ch
        Log.d(TAG, "Channel connected to $host:$port")
    }

    /** Message for a [timedOut] connect — says which hop went quiet. */
    fun timeoutMessage(timeoutMs: Int): String =
        "The jump host opened the channel to $target, but it never responded " +
            "(no SSH banner within ${timeoutMs}ms). Is something listening there?"

    override fun getInputStream(): InputStream = input!!

    override fun getOutputStream(): OutputStream = output!!

    override fun getSocket(): Socket? = null

    override fun close() {
        channel?.disconnect()
        channel = null
        input = null
        output = null
        deadline = null
    }
}
