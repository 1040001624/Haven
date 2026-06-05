package sh.haven.core.tunnel

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * JSch [Proxy] adapter that reaches the SSH server through an **authenticated**
 * SOCKS5 proxy via [ProxySocketFactory], instead of JSch's own `ProxySOCKS5`.
 *
 * Why bypass JSch (#227): mwiede/jsch 2.28.0's `ProxySOCKS5` builds the RFC 1929
 * username/password sub-negotiation using `String.length()` (the **character**
 * count) for the length octets and the `arraycopy` length, while the field
 * content is `Util.str2byte(...)` (**UTF-8 bytes**). For any non-ASCII credential
 * the two disagree, so a malformed auth packet goes out and the proxy answers
 * `server status = 01`. [ProxySocketFactory] derives the length octets from the
 * encoded byte arrays, so it is correct for non-ASCII credentials too.
 *
 * JSch hands us the destination `(host, port)` at connect time;
 * [ProxySocketFactory.createSocket] performs the proxy handshake to reach it and
 * returns a connected [Socket] whose streams carry the onward SSH traffic.
 * Mirrors [TunnelProxy] — same `connect` → stream I/O → `close` lifecycle.
 */
class AuthenticatedProxy(private val factory: ProxySocketFactory) : Proxy {

    private var socket: Socket? = null

    override fun connect(socketFactory: SocketFactory?, host: String, port: Int, timeout: Int) {
        // ProxySocketFactory owns its own connect timeout; JSch's `timeout`
        // (default 10 s) is ignored in favour of the factory's 30 s default,
        // matching TunnelProxy's "more headroom for proxy setup" stance.
        socket = factory.createSocket(host, port)
    }

    override fun getInputStream(): InputStream =
        socket?.getInputStream() ?: error("AuthenticatedProxy.connect() not called")

    override fun getOutputStream(): OutputStream =
        socket?.getOutputStream() ?: error("AuthenticatedProxy.connect() not called")

    override fun getSocket(): Socket? = socket

    override fun close() {
        try { socket?.close() } catch (_: Throwable) { /* best-effort */ }
        socket = null
    }
}
