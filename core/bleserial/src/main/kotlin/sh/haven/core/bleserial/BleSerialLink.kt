package sh.haven.core.bleserial

import java.util.UUID

/**
 * A live BLE-serial byte pipe to a connected GATT peripheral (Nordic UART
 * Service, HM-10, or a compatible BLE-UART bridge).
 *
 * Callback-shaped rather than stream-shaped because GATT delivers reads as
 * asynchronous characteristic-change notifications, not a blocking InputStream —
 * so [BleSerialSession] stays transport-agnostic and unit-testable with a fake
 * link, no Bluetooth hardware. Mirrors [sh.haven.core.usbserial.UsbSerialLink].
 */
interface BleSerialLink {
    /** Human-facing peripheral name (advertised name), if known. */
    val displayName: String?

    /**
     * Begin pumping incoming bytes (TX-characteristic notifications) to [onData].
     * [onError] fires once if the link drops on its own (out of range, peripheral
     * reset); it does NOT fire on an explicit [close]. Any bytes that arrived
     * between connect and [start] are replayed here so an on-open greeting isn't
     * lost.
     */
    fun start(onData: (ByteArray) -> Unit, onError: (Throwable) -> Unit)

    /** Write keystrokes / commands to the peripheral (RX characteristic). */
    fun write(bytes: ByteArray)

    /** Disconnect the GATT link and release it. Idempotent. */
    fun close()
}

/**
 * Which GATT service/characteristics carry the serial stream. Defaults to
 * auto-detect: the connector tries the Nordic UART Service, then HM-10. Supply
 * explicit UUIDs for a device that uses neither (e.g. a vendor whose manual just
 * says "fill in the correct UUID").
 */
data class BleSerialParams(
    val serviceUuid: UUID? = null,
    /** Characteristic the client WRITES to (peripheral's RX). */
    val writeUuid: UUID? = null,
    /** Characteristic the client SUBSCRIBES to for notifications (peripheral's TX). */
    val notifyUuid: UUID? = null,
) {
    /** A known BLE-UART profile the connector probes when no explicit UUIDs are given. */
    data class Profile(val name: String, val service: UUID, val write: UUID, val notify: UUID)

    companion object {
        private fun uuid(s: String) = UUID.fromString(s)

        /** Nordic UART Service — the de-facto standard (nRF SDK "ble_app_uart"). */
        val NUS = Profile(
            name = "Nordic UART",
            service = uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            write = uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
            notify = uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        )

        /** HM-10 / CC254x clones — one characteristic for both directions. */
        val HM10 = Profile(
            name = "HM-10",
            service = uuid("0000ffe0-0000-1000-8000-00805f9b34fb"),
            write = uuid("0000ffe1-0000-1000-8000-00805f9b34fb"),
            notify = uuid("0000ffe1-0000-1000-8000-00805f9b34fb"),
        )

        /** Client Characteristic Configuration Descriptor — enables notifications. */
        val CCCD: UUID = uuid("00002902-0000-1000-8000-00805f9b34fb")

        /** Probe order when [BleSerialParams] gives no explicit UUIDs. */
        val AUTO_PROFILES = listOf(NUS, HM10)
    }
}
