package sh.haven.core.fido

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [findFidoCtapHidInterface] device-matching coverage.
 *
 * Regression for the reported bug: FIDO2 (incl. NFC) auth stopped working when
 * a USB audio dongle was plugged in, because the old "any HID interface" match
 * selected the dongle's volume-button HID as the security key. A CTAPHID
 * interface is HID-class with an interrupt IN+OUT pair; the audio dongle's
 * consumer-control HID is interrupt-IN only, so it must NOT match — letting the
 * flow fall through to the user's NFC key.
 */
class FidoDeviceMatchTest {

    private fun endpoint(dir: Int, type: Int = UsbConstants.USB_ENDPOINT_XFER_INT): UsbEndpoint =
        mockk {
            every { direction } returns dir
            every { this@mockk.type } returns type
        }

    private fun iface(cls: Int, eps: List<UsbEndpoint>): UsbInterface = mockk {
        every { interfaceClass } returns cls
        every { endpointCount } returns eps.size
        eps.forEachIndexed { i, ep -> every { getEndpoint(i) } returns ep }
    }

    private fun device(ifaces: List<UsbInterface>): UsbDevice = mockk {
        every { interfaceCount } returns ifaces.size
        ifaces.forEachIndexed { i, f -> every { getInterface(i) } returns f }
    }

    @Test
    fun `FIDO key — HID with interrupt IN+OUT — matches`() {
        val dev = device(
            listOf(
                iface(
                    UsbConstants.USB_CLASS_HID,
                    listOf(
                        endpoint(UsbConstants.USB_DIR_IN),
                        endpoint(UsbConstants.USB_DIR_OUT),
                    ),
                ),
            ),
        )
        assertNotNull(findFidoCtapHidInterface(dev))
    }

    @Test
    fun `USB audio dongle — HID consumer-control, interrupt IN only — does not match`() {
        // Composite audio device: an Audio-class interface plus a HID interface
        // with a single interrupt-IN endpoint (the inline volume/play buttons).
        val dev = device(
            listOf(
                iface(UsbConstants.USB_CLASS_AUDIO, listOf(endpoint(UsbConstants.USB_DIR_IN))),
                iface(UsbConstants.USB_CLASS_HID, listOf(endpoint(UsbConstants.USB_DIR_IN))),
            ),
        )
        assertNull(findFidoCtapHidInterface(dev))
    }

    @Test
    fun `composite OTP+FIDO key — keyboard HID IN-only plus CTAPHID IN+OUT — matches via CTAPHID`() {
        // The OTP keyboard HID has interrupt-IN only (no OUT); the CTAPHID
        // interface has the IN+OUT pair. Must match (via the CTAPHID interface).
        val dev = device(
            listOf(
                iface(UsbConstants.USB_CLASS_HID, listOf(endpoint(UsbConstants.USB_DIR_IN))),
                iface(
                    UsbConstants.USB_CLASS_HID,
                    listOf(
                        endpoint(UsbConstants.USB_DIR_IN),
                        endpoint(UsbConstants.USB_DIR_OUT),
                    ),
                ),
            ),
        )
        assertNotNull(findFidoCtapHidInterface(dev))
    }

    @Test
    fun `HID with bulk IN+OUT (not interrupt) — does not match`() {
        // CTAPHID uses interrupt endpoints; a HID interface whose IN+OUT pair is
        // bulk is not a CTAPHID interface.
        val dev = device(
            listOf(
                iface(
                    UsbConstants.USB_CLASS_HID,
                    listOf(
                        endpoint(UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK),
                        endpoint(UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK),
                    ),
                ),
            ),
        )
        assertNull(findFidoCtapHidInterface(dev))
    }

    @Test
    fun `no HID interface at all — does not match`() {
        val dev = device(listOf(iface(UsbConstants.USB_CLASS_AUDIO, listOf(endpoint(UsbConstants.USB_DIR_IN)))))
        assertNull(findFidoCtapHidInterface(dev))
    }
}
