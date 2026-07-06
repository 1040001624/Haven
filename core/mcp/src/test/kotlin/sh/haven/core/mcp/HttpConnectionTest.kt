package sh.haven.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Pins the keep-alive connection loop (#mcp-backbone Stage 4): several
 * requests served off ONE stream pair — the property that retires the
 * connection-per-request contract (and with it the failover proxy's reason
 * to exist) — plus the close conditions: `Connection: close`, HTTP/1.0,
 * malformed input, and the per-connection request cap.
 */
class HttpConnectionTest {

    private fun request(body: String, extraHeaders: String = "", version: String = "HTTP/1.1"): String =
        "POST /mcp $version\r\nContent-Length: ${body.toByteArray().size}\r\n$extraHeaders\r\n$body"

    /** Serve [raw] and return (responses-as-text, number of handler calls). */
    private fun serve(raw: String, maxRequests: Int = MAX_KEEPALIVE_REQUESTS): Pair<String, Int> {
        val out = ByteArrayOutputStream()
        var calls = 0
        serveHttpConnection(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), out, maxRequests) { req ->
            calls++
            HttpResponse(200, "OK", "echo:${req.body}".toByteArray(), "text/plain")
        }
        return out.toString(Charsets.UTF_8) to calls
    }

    @Test
    fun `two pipelined requests are served on one connection`() {
        val (text, calls) = serve(request("""{"a":1}""") + request("""{"b":2}"""))
        assertEquals(2, calls)
        assertTrue("first response missing", text.contains("echo:{\"a\":1}"))
        assertTrue("second response missing", text.contains("echo:{\"b\":2}"))
        assertTrue("first response must advertise keep-alive", text.contains("Connection: keep-alive"))
    }

    @Test
    fun `connection close header ends the loop after one response`() {
        val (text, calls) = serve(
            request("""{"a":1}""", extraHeaders = "Connection: close\r\n") + request("""{"b":2}"""),
        )
        assertEquals("second request must not be served after Connection: close", 1, calls)
        assertTrue(text.contains("Connection: close"))
        assertFalse(text.contains("echo:{\"b\":2}"))
    }

    @Test
    fun `http 1_0 requests default to close`() {
        val (text, calls) = serve(request("""{"a":1}""", version = "HTTP/1.0") + request("""{"b":2}"""))
        assertEquals(1, calls)
        assertTrue(text.contains("Connection: close"))
    }

    @Test
    fun `the per-connection request cap closes the connection`() {
        val (text, calls) = serve(request("x") + request("y") + request("z"), maxRequests = 2)
        assertEquals(2, calls)
        assertFalse("third request beyond the cap must not be served", text.contains("echo:z"))
        // The capped (second) response must tell the client to reconnect.
        assertTrue(text.trimEnd().split("Connection: ").last().startsWith("close"))
    }

    @Test
    fun `malformed input gets an error response and a close`() {
        val out = ByteArrayOutputStream()
        var calls = 0
        serveHttpConnection(
            ByteArrayInputStream("POST /mcp HTTP/1.1\r\nContent-Length: -5\r\n\r\n".toByteArray()),
            out,
        ) { calls++; textResponse(200, "OK") }
        assertEquals(0, calls)
        val text = out.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 413"))
        assertTrue(text.contains("Connection: close"))
    }

    @Test
    fun `peer closing between requests is a clean end`() {
        val (_, calls) = serve(request("""{"a":1}""")) // EOF right after request 1
        assertEquals(1, calls)
    }

    @Test
    fun `responses carry correct content-length framing for multibyte bodies`() {
        val out = ByteArrayOutputStream()
        serveHttpConnection(
            ByteArrayInputStream(request("x").toByteArray(Charsets.UTF_8)), out,
        ) { HttpResponse(200, "OK", "中文😀".toByteArray(Charsets.UTF_8), "text/plain") }
        val text = out.toByteArray().toString(Charsets.UTF_8)
        val expected = "中文😀".toByteArray(Charsets.UTF_8).size
        assertTrue("byte-accurate Content-Length", text.contains("Content-Length: $expected"))
    }
}
