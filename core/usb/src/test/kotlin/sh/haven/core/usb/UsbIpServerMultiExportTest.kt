package sh.haven.core.usb

import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Regression coverage for #287 multi-drive: [UsbIpServer.export] now shares
 * one socket across multiple devices, dispatching each client's IMPORT
 * request by busid (previously a single fixed device, ignoring the busid the
 * wire protocol already carried). This exercises the real op handshake over
 * a real socket against two exported devices, and confirms unexporting one
 * doesn't affect the other still being served.
 */
class UsbIpServerMultiExportTest {

    private val broker = mockk<UsbBroker>()
    private val server = UsbIpServer(broker)

    @After
    fun tearDown() {
        server.stop()
    }

    // 18-byte USB device descriptor: idVendor/idProduct little-endian at
    // offsets 8-9 / 10-11 (the fields describeForImport reads).
    private fun deviceDescriptor(idVendor: Int, idProduct: Int): ByteArray {
        val b = ByteArray(18)
        b[8] = (idVendor and 0xFF).toByte(); b[9] = ((idVendor shr 8) and 0xFF).toByte()
        b[10] = (idProduct and 0xFF).toByte(); b[11] = ((idProduct shr 8) and 0xFF).toByte()
        b[17] = 1 // numConfigurations
        return b
    }

    private fun sendImport(port: Int, busid: String): ImportReply {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port), 2000)
            val out = DataOutputStream(sock.getOutputStream())
            out.writeShort(UsbIpProtocol.VERSION)
            out.writeShort(UsbIpProtocol.OP_REQ_IMPORT)
            out.writeInt(0)
            val busidBytes = ByteArray(32); busid.toByteArray(Charsets.US_ASCII).copyInto(busidBytes)
            out.write(busidBytes)
            out.flush()

            val input = DataInputStream(sock.getInputStream())
            input.readUnsignedShort() // version
            input.readUnsignedShort() // OP_REP_IMPORT
            val status = input.readInt()
            if (status != 0) return ImportReply(ok = false, busid = null, idVendor = -1)

            fun fixedString(len: Int): String {
                val buf = ByteArray(len); input.readFully(buf)
                val end = buf.indexOf(0.toByte()).let { if (it < 0) len else it }
                return String(buf, 0, end, Charsets.US_ASCII)
            }
            fixedString(256) // path
            val replyBusid = fixedString(32)
            input.readInt(); input.readInt(); input.readInt() // busnum, devnum, speed
            val idVendor = input.readUnsignedShort()
            return ImportReply(ok = true, busid = replyBusid, idVendor = idVendor)
        }
    }

    private data class ImportReply(val ok: Boolean, val busid: String?, val idVendor: Int)

    @Test
    fun `two exported devices dispatch to the right descriptors by busid`() {
        every { broker.rawDescriptors("/dev/bus/usb/001/002") } returns deviceDescriptor(idVendor = 0x1111, idProduct = 0x2222)
        every { broker.rawDescriptors("/dev/bus/usb/003/004") } returns deviceDescriptor(idVendor = 0x3333, idProduct = 0x4444)

        val busid1 = server.export("/dev/bus/usb/001/002", port = 0)
        val busid2 = server.export("/dev/bus/usb/003/004")
        assertEquals("1-2", busid1)
        assertEquals("3-4", busid2)

        val port = server.boundPort!!
        val reply1 = sendImport(port, busid1)
        val reply2 = sendImport(port, busid2)

        assertTrue(reply1.ok); assertTrue(reply2.ok)
        assertEquals(busid1, reply1.busid); assertEquals(0x1111, reply1.idVendor)
        assertEquals(busid2, reply2.busid); assertEquals(0x3333, reply2.idVendor)
    }

    @Test
    fun `unexporting one device leaves the other reachable`() {
        every { broker.rawDescriptors("/dev/bus/usb/001/002") } returns deviceDescriptor(idVendor = 0x1111, idProduct = 0x2222)
        every { broker.rawDescriptors("/dev/bus/usb/003/004") } returns deviceDescriptor(idVendor = 0x3333, idProduct = 0x4444)

        val busid1 = server.export("/dev/bus/usb/001/002")
        val busid2 = server.export("/dev/bus/usb/003/004")
        val port = server.boundPort!!

        server.unexport(busid1)
        assertTrue("server should stay up — busid2 is still exported", server.isRunning)

        val reply1 = sendImport(port, busid1)
        val reply2 = sendImport(port, busid2)
        assertTrue("unexported busid must fail the IMPORT", !reply1.ok)
        assertTrue(reply2.ok); assertEquals(0x3333, reply2.idVendor)

        server.unexport(busid2)
        assertTrue("server should stop once nothing is exported", !server.isRunning)
    }

    @Test
    fun `an unrequested busid is refused, not silently served`() {
        every { broker.rawDescriptors("/dev/bus/usb/001/002") } returns deviceDescriptor(idVendor = 0x1111, idProduct = 0x2222)
        server.export("/dev/bus/usb/001/002")
        val port = server.boundPort!!

        val reply = sendImport(port, "9-9")
        assertTrue("an unexported busid must not get any device back", !reply.ok)
    }
}
