package sh.haven.core.tunnel

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CloudflareAccessTunnelTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val openServerSockets = mutableListOf<WebSocket>()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After fun tearDown() {
        // MockWebServer's shutdown waits for in-flight WS dispatchers
        // to drain. Closing the server-side socket releases that thread
        // so shutdown can complete.
        openServerSockets.forEach { runCatching { it.close(1000, null) } }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    /**
     * Helper: configure the next request to upgrade to a WebSocket and
     * echo binary frames straight back to the client. Returns a queue
     * the test can poll to inspect what bytes the server received.
     */
    private fun echoOnce(): LinkedBlockingQueue<ByteString> {
        val received = LinkedBlockingQueue<ByteString>()
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                synchronized(openServerSockets) { openServerSockets.add(ws) }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                received.add(bytes)
                ws.send(bytes)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        return received
    }

    @Test
    fun `dial sends CF_Authorization cookie and ssh subprotocol`() {
        echoOnce()
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "test-jwt-token",
            httpClient = client,
            gatewayUrlOverride = server.url("/cdn-cgi/access/ssh-gateway").toString(),
        )
        val conn = tunnel.dial("ssh.example.com", 22, 3_000)
        conn.close()

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("CF_Authorization=test-jwt-token", req.getHeader("Cookie"))
        assertEquals("ssh", req.getHeader("Sec-WebSocket-Protocol"))
        assertEquals("/cdn-cgi/access/ssh-gateway", req.path)
        tunnel.close()
    }

    @Test
    fun `bytes round-trip through binary frames`() {
        val serverInbox = echoOnce()
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "j",
            httpClient = client,
            gatewayUrlOverride = server.url("/cdn-cgi/access/ssh-gateway").toString(),
        )
        val conn = tunnel.dial("ssh.example.com", 22, 3_000)

        val payload = "SSH-2.0-OpenSSH_9.0\r\n".toByteArray()
        conn.outputStream.write(payload)
        conn.outputStream.flush()

        val seenByServer = serverInbox.poll(2, TimeUnit.SECONDS)!!
        assertArrayEquals(payload, seenByServer.toByteArray())

        // Echoed back into inputStream
        val readBack = ByteArray(payload.size)
        var off = 0
        while (off < payload.size) {
            val n = conn.inputStream.read(readBack, off, payload.size - off)
            check(n > 0) { "EOF before all bytes read" }
            off += n
        }
        assertArrayEquals(payload, readBack)
        conn.close()
        tunnel.close()
    }

    @Test
    fun `dial rejects hostname mismatch with a clear error`() {
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "j",
            httpClient = client,
            gatewayUrlOverride = server.url("/cdn-cgi/access/ssh-gateway").toString(),
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            tunnel.dial("other.example.com", 22, 3_000)
        }
        assertTrue(ex.message!!.contains("ssh.example.com"))
        assertTrue(ex.message!!.contains("other.example.com"))
        tunnel.close()
    }

    @Test
    fun `tunnel close tears down active connections`() {
        echoOnce()
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "j",
            httpClient = client,
            gatewayUrlOverride = server.url("/cdn-cgi/access/ssh-gateway").toString(),
        )
        val conn = tunnel.dial("ssh.example.com", 22, 3_000)
        tunnel.close()
        // After tunnel close, writes should fail rather than silently succeed.
        val writeFailed = runCatching {
            conn.outputStream.write(byteArrayOf(1, 2, 3))
            conn.outputStream.flush()
        }.isFailure
        assertTrue("write after close should fail", writeFailed)
    }
}
