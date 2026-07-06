package sh.haven.core.mcp

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Keep-alive HTTP/1.1 connection loop (#mcp-backbone Stage 4). Serves up to
 * [MAX_KEEPALIVE_REQUESTS] requests off one stream pair, which is what lets an
 * MCP client hold a single warm connection instead of dialling per request —
 * the contract the workstation failover proxy existed to paper over.
 */

/**
 * Requests served per connection before the server closes it. A finite cap so
 * one peer can't hold a worker thread forever; far above anything a real MCP
 * session sends in one connection, and a client just reconnects when it hits.
 */
const val MAX_KEEPALIVE_REQUESTS: Int = 1000

/** One HTTP response as data — built by the request handler, written by the loop. */
class HttpResponse(
    val status: Int,
    val statusText: String,
    val body: ByteArray,
    val contentType: String? = null,
    /** Extra response headers, e.g. `Mcp-Session-Id`, CORS. */
    val headers: List<Pair<String, String>> = emptyList(),
)

/** Convenience: a plain-text response ([status] with [text] as both reason and body). */
fun textResponse(status: Int, text: String): HttpResponse =
    HttpResponse(status, text, text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")

/**
 * Serve requests off one connection until the peer closes, asks to close
 * (`Connection: close`), speaks a non-1.1 HTTP version, sends something
 * malformed, or [maxRequests] is reached. [handler] maps each parsed request
 * to a response; the loop owns framing and the `Connection` header.
 *
 * A read timeout (idle keep-alive peer) after at least one served request is a
 * normal close; on the FIRST request it propagates so the caller can log it
 * (same visibility the one-shot server had).
 */
fun serveHttpConnection(
    input: InputStream,
    output: OutputStream,
    maxRequests: Int = MAX_KEEPALIVE_REQUESTS,
    handler: (ParsedHttpRequest) -> HttpResponse,
) {
    // Buffered so per-byte header scanning isn't a syscall storm.
    val bin = if (input is BufferedInputStream) input else BufferedInputStream(input)
    var served = 0
    while (served < maxRequests) {
        val res = try {
            parseHttpRequest(bin)
        } catch (e: java.net.SocketTimeoutException) {
            if (served == 0) throw e
            return // idle keep-alive connection timing out is a normal close
        }
        when (res) {
            is HttpParseResult.Closed -> return
            is HttpParseResult.Fail -> {
                writeHttpResponse(output, textResponse(res.status, res.reason), keepAlive = false)
                return
            }
            is HttpParseResult.Ok -> {
                val req = res.request
                served++
                val response = handler(req)
                val peerWantsClose =
                    req.headers["connection"]?.contains("close", ignoreCase = true) == true
                val keep = !peerWantsClose &&
                    req.httpVersion.equals("HTTP/1.1", ignoreCase = true) &&
                    served < maxRequests
                writeHttpResponse(output, response, keepAlive = keep)
                if (!keep) return
            }
        }
    }
}

/** Write [response] with correct framing and an explicit `Connection` header. */
fun writeHttpResponse(out: OutputStream, response: HttpResponse, keepAlive: Boolean) {
    val head = buildString {
        append("HTTP/1.1 ${response.status} ${response.statusText}\r\n")
        response.contentType?.let { append("Content-Type: $it\r\n") }
        append("Content-Length: ${response.body.size}\r\n")
        response.headers.forEach { (k, v) -> append("$k: $v\r\n") }
        append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
        append("\r\n")
    }
    out.write(head.toByteArray(Charsets.UTF_8))
    if (response.body.isNotEmpty()) out.write(response.body)
    out.flush()
}
