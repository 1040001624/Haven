package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A proxied SSH session has no socket, so JSch cannot time out the read that
 * waits for the target's banner — a silent target blocks forever, with nothing
 * written to the connection log (#383). The deadline turns that into a normal
 * failure, and must NOT interfere once the target has spoken: everything slow
 * but legitimate (password, TOTP, FIDO touch) happens after the first byte.
 */
class FirstByteDeadlineTest {

    @Test
    fun `fires when the stream stays silent`() {
        val silent = PipedInputStream(PipedOutputStream()) // never written to
        val fired = CountDownLatch(1)
        val stream = FirstByteDeadlineInputStream(silent, 100) { fired.countDown() }

        assertTrue("deadline should fire on a silent stream", fired.await(3, TimeUnit.SECONDS))
        assertTrue(stream.expired)
    }

    @Test
    fun `does not fire once the stream has spoken`() {
        val spoke = ByteArrayInputStream("SSH-2.0-OpenSSH_9.6\r\n".toByteArray())
        val fired = AtomicBoolean(false)
        val stream = FirstByteDeadlineInputStream(spoke, 150) { fired.set(true) }

        assertEquals('S'.code, stream.read())   // the target speaks — disarm
        Thread.sleep(400)                       // well past the deadline

        assertFalse("deadline must not fire after the first byte", fired.get())
        assertFalse(stream.expired)
    }

    @Test
    fun `a bulk read also disarms the deadline`() {
        val spoke = ByteArrayInputStream("SSH-2.0-OpenSSH_9.6\r\n".toByteArray())
        val fired = AtomicBoolean(false)
        val stream = FirstByteDeadlineInputStream(spoke, 150) { fired.set(true) }

        val buf = ByteArray(8)
        assertEquals(8, stream.read(buf, 0, 8))
        Thread.sleep(400)

        assertFalse(fired.get())
    }

    @Test
    fun `close cancels a pending deadline`() {
        val silent = PipedInputStream(PipedOutputStream())
        val fired = AtomicBoolean(false)
        val stream = FirstByteDeadlineInputStream(silent, 150) { fired.set(true) }

        stream.close()
        Thread.sleep(400)

        assertFalse("a closed stream must not fire its deadline", fired.get())
    }
}
