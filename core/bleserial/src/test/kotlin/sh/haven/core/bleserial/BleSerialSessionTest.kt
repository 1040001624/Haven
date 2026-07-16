package sh.haven.core.bleserial

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A [BleSerialLink] with no Bluetooth behind it — drives the session/manager in tests. */
private class FakeBleLink(override val displayName: String? = "FakeBLE") : BleSerialLink {
    var onData: ((ByteArray) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    val written = mutableListOf<ByteArray>()
    var closed = false

    override fun start(onData: (ByteArray) -> Unit, onError: (Throwable) -> Unit) {
        this.onData = onData
        this.onError = onError
    }

    override fun write(bytes: ByteArray) { written.add(bytes) }
    override fun close() { closed = true }

    /** Simulate a notification from the peripheral. */
    fun emit(bytes: ByteArray) = onData?.invoke(bytes)
    fun dropLink() = onError?.invoke(RuntimeException("out of range"))
}

class BleSerialSessionTest {

    private fun managerWith(link: FakeBleLink): BleSerialSessionManager {
        // ApplicationContext is only touched by the lazy connector, which the
        // openLink seam replaces — so a null context is never dereferenced.
        @Suppress("UNCHECKED_CAST")
        val mgr = BleSerialSessionManager(context = mockContext())
        mgr.openLink = { _, _ -> link }
        return mgr
    }

    private fun mockContext(): android.content.Context =
        io.mockk.mockk(relaxed = true)

    @Test
    fun `connect then create terminal delivers peripheral bytes`() = runTest {
        val link = FakeBleLink()
        val mgr = managerWith(link)
        val id = mgr.registerSession("p1", "nRF", "AA:BB:CC:DD:EE:FF")

        mgr.connectSession(id)
        assertEquals(BleSerialSessionManager.SessionState.Status.CONNECTED, mgr.sessions.value[id]!!.status)
        assertEquals("FakeBLE", mgr.sessions.value[id]!!.deviceName)

        val received = mutableListOf<Byte>()
        mgr.createTerminalSession(id) { buf, off, len -> received += buf.copyOfRange(off, off + len).toList() }
        link.emit("hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), received.toByteArray())
    }

    @Test
    fun `sendInput reaches the link as UTF-8`() = runTest {
        val link = FakeBleLink()
        val mgr = managerWith(link)
        val id = mgr.registerSession("p1", "nRF", "AA:BB:CC:DD:EE:FF")
        mgr.connectSession(id)
        mgr.createTerminalSession(id) { _, _, _ -> }

        mgr.sendInput(id, "G28\n")
        assertEquals(1, link.written.size)
        assertArrayEquals("G28\n".toByteArray(), link.written[0])
    }

    @Test
    fun `link drop marks the session disconnected`() = runTest {
        val link = FakeBleLink()
        val mgr = managerWith(link)
        val id = mgr.registerSession("p1", "nRF", "AA:BB:CC:DD:EE:FF")
        mgr.connectSession(id)
        mgr.createTerminalSession(id) { _, _, _ -> }

        link.dropLink()
        assertEquals(BleSerialSessionManager.SessionState.Status.DISCONNECTED, mgr.sessions.value[id]!!.status)
        assertTrue(link.closed)
    }

    @Test
    fun `detach stops delivery without closing the link`() = runTest {
        val link = FakeBleLink()
        val mgr = managerWith(link)
        val id = mgr.registerSession("p1", "nRF", "AA:BB:CC:DD:EE:FF")
        mgr.connectSession(id)
        val received = mutableListOf<Byte>()
        mgr.createTerminalSession(id) { buf, off, len -> received += buf.copyOfRange(off, off + len).toList() }

        mgr.detachTerminalSession(id)
        link.emit("dropped".toByteArray())
        assertTrue(received.isEmpty())
        assertTrue(!link.closed)
    }
}
