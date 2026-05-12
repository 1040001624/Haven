package sh.haven.core.tunnel

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [Tunnel] implementation backed by Cloudflare Access SSH — the same
 * mechanism `cloudflared access ssh --hostname <h>` uses on desktop.
 *
 * Per-hostname proxy: every `dial(host, …)` must target the configured
 * [hostname]; port is ignored because the WebSocket gateway terminates
 * the connection at the Access application, which itself fronts the SSH
 * service on the server side of the Cloudflare Tunnel.
 *
 * Each dial opens a fresh WebSocket to
 * `wss://<hostname>/cdn-cgi/access/ssh-gateway` with
 * `Cookie: CF_Authorization=<jwt>` and `Sec-WebSocket-Protocol: ssh`.
 * Binary frames carry opaque SSH bytes both directions; text frames are
 * not expected and are treated as errors.
 *
 * The wire format is reverse-engineered from `cloudflared` (cmd/cloudflared
 * /access/ssh.go + connection/connection.go) — Cloudflare don't publish
 * a spec. Marked Experimental in the UI; if CF changes the path or
 * framing we'll need to update this here.
 *
 * Lifetime: the [OkHttpClient] is owned by the caller (Hilt-provided
 * singleton) and not closed in [close] — only the per-dial WebSockets
 * are torn down. Since Access has no L3 surface there's no
 * [socksAddress] implementation; rclone/IronRDP can't tunnel through
 * this backend (would need a separate SOCKS-fronting proxy).
 *
 * Thread-safety: `dial` may be called concurrently. Each dial owns its
 * own WebSocket; the parent tunnel only tracks them for [close] to
 * cancel any still-live streams.
 */
class CloudflareAccessTunnel internal constructor(
    private val hostname: String,
    private val jwt: String,
    private val httpClient: OkHttpClient,
    /** Test seam. Production code passes null; tests point at MockWebServer. */
    private val gatewayUrlOverride: String? = null,
) : Tunnel {

    companion object {
        /**
         * Public factory for callers outside `:core:tunnel` (the
         * production path goes through [DefaultTunnelFactory] and never
         * needs this). Used by the Settings "Test connection" button to
         * build a transient tunnel without going through [TunnelManager]
         * — the test deliberately isn't ref-counted into any live
         * session.
         */
        fun forTest(
            hostname: String,
            jwt: String,
            httpClient: OkHttpClient,
        ): CloudflareAccessTunnel = CloudflareAccessTunnel(
            hostname = hostname,
            jwt = jwt,
            httpClient = httpClient,
        )
    }

    private val live = CopyOnWriteArraySet<WebSocketTunneledConnection>()

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        require(host.equals(hostname, ignoreCase = true)) {
            "CloudflareAccessTunnel is bound to '$hostname'; cannot dial '$host'"
        }

        val url = gatewayUrlOverride ?: "https://$hostname/cdn-cgi/access/ssh-gateway"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "CF_Authorization=$jwt")
            .header("Sec-WebSocket-Protocol", "ssh")
            .build()

        // Use a per-dial client with the requested call timeout so reads
        // don't block forever if the SSH gateway stalls. The shared
        // singleton OkHttpClient is reused via newBuilder() — only the
        // call-timeout differs per dial.
        val client = if (timeoutMs > 0) {
            httpClient.newBuilder()
                .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
        } else httpClient

        val conn = WebSocketTunneledConnection(hostname) { live.remove(it) }
        live.add(conn)
        try {
            conn.start(client, request, timeoutMs)
        } catch (t: Throwable) {
            live.remove(conn)
            throw t
        }
        return conn
    }

    override fun socksAddress(): InetSocketAddress? = null

    override fun close() {
        // Snapshot to avoid CME if a dial finishes concurrently.
        live.toList().forEach { runCatching { it.close() } }
        live.clear()
    }
}

/**
 * [TunneledConnection] backed by an OkHttp [WebSocket]. Inbound binary
 * frames are piped into [inputStream]; [outputStream] writes are batched
 * and sent as single binary frames per flush. SSH already produces
 * suitably-sized writes, so a buffered output is more than enough.
 */
private class WebSocketTunneledConnection(
    private val hostname: String,
    private val onClose: (WebSocketTunneledConnection) -> Unit,
) : TunneledConnection {

    private val pipeBufferSize = 64 * 1024
    private val inboundSink = PipedOutputStream()
    private val inboundSource = PipedInputStream(inboundSink, pipeBufferSize)
    private val openedLatch = CountDownLatch(1)
    private val openError = AtomicReference<Throwable?>(null)
    private val webSocket = AtomicReference<WebSocket?>(null)
    @Volatile private var closed = false

    override val inputStream: InputStream = inboundSource

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf((b and 0xFF).toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Cloudflare Access tunnel closed")
            if (len == 0) return
            val ws = webSocket.get() ?: throw IOException("Cloudflare Access WebSocket not yet open")
            val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            val ok = ws.send(ByteString.of(*slice))
            if (!ok) throw IOException("Cloudflare Access WebSocket send queue closed")
        }
    }

    fun start(client: OkHttpClient, request: Request, timeoutMs: Int) {
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket.set(ws)
                openedLatch.countDown()
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (closed) return
                try {
                    bytes.write(inboundSink)
                    inboundSink.flush()
                } catch (t: Throwable) {
                    // Reader closed before WS finished — common on
                    // disconnect, not worth re-raising.
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // The SSH gateway never sends text frames. If we see one,
                // treat it as a protocol error so callers don't silently
                // drop bytes.
                fail(ws, IOException("Cloudflare Access gateway sent unexpected text frame"))
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                closeQuietly()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                closeQuietly()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                fail(ws, mapFailure(t, response))
            }

            private fun fail(ws: WebSocket, error: Throwable) {
                if (openError.compareAndSet(null, error)) {
                    openedLatch.countDown()
                }
                closeQuietly()
            }
        }

        client.newWebSocket(request, listener)

        val awaitMs = if (timeoutMs > 0) timeoutMs.toLong() else 30_000L
        val opened = openedLatch.await(awaitMs, TimeUnit.MILLISECONDS)
        openError.get()?.let { throw it }
        if (!opened) {
            close()
            throw IOException("Cloudflare Access WebSocket open timed out after ${awaitMs}ms")
        }
    }

    /**
     * Translate OkHttp's failure surface into a connection-log-friendly
     * exception. HTTP 401/403 from the upgrade response usually means an
     * expired or wrong-audience JWT — surface that explicitly rather
     * than the bare "Expected HTTP 101" message OkHttp emits.
     *
     * We're flying without a documented spec for the SSH-gateway wire
     * format (the protocol is reverse-engineered from cloudflared), so
     * the diagnostic line gets aggressive: every failure carries the
     * status code, the Cloudflare-specific debug headers we can find
     * (cf-ray for support correlation, cf-mitigated for WAF blocks,
     * Server, Sec-WebSocket-Protocol negotiation, WWW-Authenticate),
     * and the first 256 bytes of any HTML/text response body. The
     * point is that the *first* test against a real Access tenant
     * (issue #154) yields actionable info, not a generic
     * "Expected HTTP 101" round-trip.
     */
    private fun mapFailure(t: Throwable, response: Response?): Throwable {
        val code = response?.code
        val detail = buildDiagnostics(response)
        val base = when (code) {
            401, 403 -> "Cloudflare Access rejected the JWT for $hostname (HTTP $code) — re-authenticate"
            in 500..599 -> "Cloudflare Access gateway error for $hostname (HTTP $code)"
            null -> "Cloudflare Access WebSocket to $hostname failed: ${t.message}"
            else -> "Cloudflare Access WebSocket to $hostname failed: HTTP $code ${t.message ?: ""}".trim()
        }
        return IOException(if (detail.isEmpty()) base else "$base — $detail", t)
    }

    /**
     * Build a `key=value; key=value` diagnostic string from the upgrade
     * response. Empty if [response] is null. Body excerpt is included
     * only if the content-type looks textual and the body is small.
     */
    private fun buildDiagnostics(response: Response?): String {
        if (response == null) return ""
        val parts = mutableListOf<String>()
        for (name in DIAG_HEADERS) {
            response.header(name)?.let { parts += "$name=${redact(it)}" }
        }
        // Body excerpt — guarded by content-type and length. OkHttp
        // peekBody buffers without consuming the upstream source, so
        // we don't break any downstream consumer of `response`.
        val contentType = response.header("Content-Type").orEmpty()
        val textual = contentType.startsWith("text/", ignoreCase = true) ||
            contentType.contains("html", ignoreCase = true) ||
            contentType.contains("json", ignoreCase = true) ||
            contentType.isEmpty()
        if (textual) {
            runCatching {
                val peeked = response.peekBody(BODY_PEEK_BYTES)
                val excerpt = peeked.string().take(BODY_EXCERPT_CHARS).replace(Regex("\\s+"), " ").trim()
                if (excerpt.isNotEmpty()) parts += "body=\"$excerpt\""
            }
        }
        return parts.joinToString("; ")
    }

    /** Redact JWT-shaped values so debug strings don't leak credentials. */
    private fun redact(value: String): String {
        if (value.length > 40 && value.contains('.')) return "<redacted ${value.length} chars>"
        return value
    }

    private companion object {
        /** Headers we want in the diagnostic line, in priority order. */
        val DIAG_HEADERS = listOf(
            "cf-ray",
            "cf-mitigated",
            "Server",
            "Sec-WebSocket-Protocol",
            "WWW-Authenticate",
            "Location",
        )
        const val BODY_PEEK_BYTES = 1024L
        const val BODY_EXCERPT_CHARS = 256
    }

    override fun close() {
        if (closed) return
        closed = true
        webSocket.getAndSet(null)?.let { runCatching { it.close(1000, null) } }
        runCatching { inboundSink.close() }
        runCatching { inboundSource.close() }
        onClose(this)
    }

    private fun closeQuietly() {
        runCatching { inboundSink.close() }
    }
}
