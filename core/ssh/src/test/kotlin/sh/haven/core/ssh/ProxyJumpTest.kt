package sh.haven.core.ssh

import com.jcraft.jsch.Channel
import com.jcraft.jsch.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * The jump channel's streams must be bound BEFORE the channel is connected.
 *
 * JSch installs the server→client pipe inside `Channel.getInputStream()`
 * (`io.setOutputStream(PassiveOutputStream)`), and `Channel.write()` swallows
 * the NullPointerException when that pipe isn't there yet — so bytes arriving
 * before it exists are silently discarded. The target's SSH version banner is
 * sent the moment the direct-tcpip channel opens, so connecting first and
 * fetching the streams afterwards races the banner into the void: the KEX read
 * then blocks forever, and a proxied session has no socket for the connect
 * timeout to fire on (#381).
 */
class ProxyJumpTest {

    @Test
    fun `binds channel streams before connecting the channel`() {
        val input = ByteArrayInputStream("SSH-2.0-OpenSSH_9.6\r\n".toByteArray())
        val output = ByteArrayOutputStream()
        val channel = mockk<Channel>(relaxed = true)
        every { channel.inputStream } returns input
        every { channel.outputStream } returns output

        val session = mockk<Session>()
        every { session.isConnected } returns true
        every { session.getStreamForwarder("internal.example", 22) } returns channel

        val proxy = ProxyJump(session)
        proxy.connect(null, "internal.example", 22, 10_000)

        verifyOrder {
            channel.inputStream
            channel.outputStream
            channel.connect(10_000)
        }
        // The input is wrapped in the first-byte deadline (#383), so it isn't the
        // same object — but it must read through to the channel's stream, and the
        // output is handed over untouched.
        assertEquals('S'.code, proxy.inputStream.read())
        assertSame(output, proxy.outputStream)
    }

    @Test
    fun `a silent target trips the deadline and the channel is torn down`() {
        // The target opens but never sends its banner. With no socket behind the
        // channel JSch would block on the read forever (#383) — the deadline must
        // disconnect the channel, which unblocks it, and report the timeout so the
        // caller can say which hop went quiet.
        val silent = PipedInputStream(PipedOutputStream())  // never written to
        val channel = mockk<Channel>(relaxed = true)
        every { channel.inputStream } returns silent
        every { channel.outputStream } returns ByteArrayOutputStream()
        val session = mockk<Session>()
        every { session.isConnected } returns true
        every { session.getStreamForwarder(any(), any()) } returns channel

        val proxy = ProxyJump(session)
        proxy.connect(null, "internal.example", 22, 150)

        // disconnect() is what unblocks JSch's pending read
        verify(timeout = 3000) { channel.disconnect() }
        assertTrue(proxy.timedOut)
        assertTrue(proxy.timeoutMessage(150).contains("internal.example:22"))
    }

    @Test
    fun `a target that sends its banner is not timed out`() {
        val banner = ByteArrayInputStream("SSH-2.0-OpenSSH_9.6\r\n".toByteArray())
        val channel = mockk<Channel>(relaxed = true)
        every { channel.inputStream } returns banner
        every { channel.outputStream } returns ByteArrayOutputStream()
        val session = mockk<Session>()
        every { session.isConnected } returns true
        every { session.getStreamForwarder(any(), any()) } returns channel

        val proxy = ProxyJump(session)
        proxy.connect(null, "internal.example", 22, 150)
        proxy.inputStream.read()   // the target speaks — deadline disarms
        Thread.sleep(400)          // well past it

        assertFalse(proxy.timedOut)
        verify(exactly = 0) { channel.disconnect() }
    }

    @Test
    fun `the timeout survives close`() {
        // JSch closes the proxy on its way out of a failed Session.connect(), so
        // the caller only gets to ask "did it time out?" AFTER close(). If close
        // cleared the flag, the connect would be reported as a generic
        // "connection is closed by foreign host" — which is what happened on the
        // first device run of this fix (#383).
        val silent = PipedInputStream(PipedOutputStream())
        val channel = mockk<Channel>(relaxed = true)
        every { channel.inputStream } returns silent
        every { channel.outputStream } returns ByteArrayOutputStream()
        val session = mockk<Session>()
        every { session.isConnected } returns true
        every { session.getStreamForwarder(any(), any()) } returns channel

        val proxy = ProxyJump(session)
        proxy.connect(null, "internal.example", 22, 150)
        verify(timeout = 3000) { channel.disconnect() }

        proxy.close()

        assertTrue("close() must not erase the timeout", proxy.timedOut)
    }

    @Test
    fun `close disconnects the channel`() {
        val channel = mockk<Channel>(relaxed = true)
        every { channel.inputStream } returns ByteArrayInputStream(ByteArray(0))
        every { channel.outputStream } returns ByteArrayOutputStream()
        val session = mockk<Session>()
        every { session.isConnected } returns true
        every { session.getStreamForwarder(any(), any()) } returns channel

        ProxyJump(session).apply {
            connect(null, "internal.example", 22, 10_000)
            close()
        }

        io.mockk.verify { channel.disconnect() }
    }
}
