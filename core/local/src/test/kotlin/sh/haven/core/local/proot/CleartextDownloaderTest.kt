package sh.haven.core.local.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Exercises [downloadCleartextFallback]'s raw-socket HTTP/1.1 parsing (#284)
 * against a real loopback [ServerSocket] — no Android/network dependency, so
 * this runs in a plain JVM unit test. Covers the three response shapes a real
 * static file server can send: plain Content-Length, chunked
 * transfer-encoding, and a redirect hop.
 */
class CleartextDownloaderTest {

    /** Starts a one-shot loopback server that writes [rawResponse] to the first client and closes. */
    private fun serveOnce(rawResponse: ByteArray): Int {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            server.accept().use { sock ->
                readRequestLine(sock)
                sock.getOutputStream().write(rawResponse)
                sock.getOutputStream().flush()
            }
            server.close()
        }
        return server.localPort
    }

    private fun readRequestLine(sock: Socket) {
        // Drain the request headers so the client's write doesn't block on
        // a full send buffer for large requests (irrelevant here, but keeps
        // the exchange well-formed).
        val input = sock.getInputStream()
        val buf = ByteArray(4096)
        var seenEnd = false
        var total = ByteArray(0)
        while (!seenEnd) {
            val n = input.read(buf)
            if (n < 0) break
            total += buf.copyOf(n)
            if (String(total).contains("\r\n\r\n")) seenEnd = true
        }
    }

    @Test
    fun `downloads a plain content-length body`() {
        val body = "hello from a LAN mirror\n".repeat(100)
        val response = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n" + body
            ).toByteArray()
        val port = serveOnce(response)
        val dest = File.createTempFile("cleartext", ".bin")
        downloadCleartextFallback("http://127.0.0.1:$port/file.bin", dest) {}
        assertEquals(body, dest.readText())
        dest.delete()
    }

    @Test
    fun `downloads a chunked body`() {
        val part1 = "first-chunk-data-"
        val part2 = "second-chunk-data"
        val response = (
            "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "${part1.length.toString(16)}\r\n$part1\r\n" +
                "${part2.length.toString(16)}\r\n$part2\r\n" +
                "0\r\n\r\n"
            ).toByteArray()
        val port = serveOnce(response)
        val dest = File.createTempFile("cleartext", ".bin")
        downloadCleartextFallback("http://127.0.0.1:$port/file.bin", dest) {}
        assertEquals(part1 + part2, dest.readText())
        dest.delete()
    }

    @Test
    fun `follows one redirect hop`() {
        // Two servers: the first replies 302 pointing at the second, which
        // serves the real body — mirrors a static-file host redirecting to
        // a CDN or a differently-cased path.
        val body = "redirected payload"
        val targetResponse = (
            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"
            ).toByteArray()
        val targetPort = serveOnce(targetResponse)
        val redirectResponse = (
            "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$targetPort/real-file.bin\r\nContent-Length: 0\r\n\r\n"
            ).toByteArray()
        val redirectPort = serveOnce(redirectResponse)
        val dest = File.createTempFile("cleartext", ".bin")
        downloadCleartextFallback("http://127.0.0.1:$redirectPort/file.bin", dest) {}
        assertEquals(body, dest.readText())
        dest.delete()
    }

    @Test
    fun `throws on a non-2xx non-redirect status`() {
        val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()
        val port = serveOnce(response)
        val dest = File.createTempFile("cleartext", ".bin")
        var threw = false
        try {
            downloadCleartextFallback("http://127.0.0.1:$port/missing.bin", dest) {}
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(e.message.orEmpty().contains("404"))
        }
        assertTrue("expected an IOException for a 404 response", threw)
        dest.delete()
    }
}
