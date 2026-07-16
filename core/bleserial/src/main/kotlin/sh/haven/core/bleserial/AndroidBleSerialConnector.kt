package sh.haven.core.bleserial

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "BleSerialConnector"

/**
 * Opens a BLE-serial (GATT) link to a peripheral by MAC address, resolving the
 * Nordic UART Service (or HM-10, or explicit UUIDs), enabling notifications, and
 * negotiating a larger MTU. The GATT API is asynchronous and callback-driven;
 * [connect] drives that state machine to completion and blocks until the link is
 * usable, so callers (mirroring the RFCOMM #406 path) treat it like a blocking
 * dial. Must run off the main thread. BLUETOOTH_CONNECT must be granted.
 */
class AndroidBleSerialConnector(private val context: Context) {

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT gated at the call site
    fun connect(address: String, params: BleSerialParams = BleSerialParams()): BleSerialLink {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IllegalStateException("This device has no Bluetooth adapter")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        val link = AndroidBleSerialLink(context, params)
        link.connectBlocking(device, CONNECT_TIMEOUT_MS)
        return link
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}

/**
 * [BleSerialLink] backed by an open [BluetoothGatt]. Owns the GATT callback, a
 * pre-[start] notification buffer, and a single-in-flight write queue (BLE
 * permits one GATT operation at a time).
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION") // pre-33 setValue/writeCharacteristic path — works on all minSdk 26+ levels
internal class AndroidBleSerialLink(
    private val context: Context,
    private val params: BleSerialParams,
) : BleSerialLink {

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var name: String? = null
    @Volatile private var mtuPayload = DEFAULT_MTU - 3

    @Volatile private var onData: ((ByteArray) -> Unit)? = null
    @Volatile private var onError: ((Throwable) -> Unit)? = null
    private val preStartBuffer = ArrayDeque<ByteArray>()

    private val ready = CountDownLatch(1)
    @Volatile private var readyError: Throwable? = null
    @Volatile private var started = false
    @Volatile private var closed = false

    // Single-in-flight write queue; drained on onCharacteristicWrite.
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    override val displayName: String? get() = name

    fun connectBlocking(device: BluetoothDevice, timeoutMs: Long) {
        name = runCatching { device.name }.getOrNull()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            throw IllegalStateException("connectGatt returned null for ${device.address}")
        }
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw IllegalStateException("BLE connect timed out after ${timeoutMs}ms")
        }
        readyError?.let { close(); throw it }
    }

    override fun start(onData: (ByteArray) -> Unit, onError: (Throwable) -> Unit) {
        synchronized(preStartBuffer) {
            this.onData = onData
            this.onError = onError
            started = true
            while (preStartBuffer.isNotEmpty()) onData(preStartBuffer.poll())
        }
    }

    override fun write(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        val ch = writeChar ?: return
        val useNoResponse =
            (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        synchronized(writeQueue) {
            var i = 0
            while (i < bytes.size) {
                val end = minOf(i + mtuPayload, bytes.size)
                writeQueue.add(bytes.copyOfRange(i, end))
                i = end
            }
            pumpWrites(useNoResponse)
        }
    }

    // Caller holds writeQueue lock.
    private fun pumpWrites(useNoResponse: Boolean) {
        if (writeInFlight) return
        val g = gatt ?: return
        val ch = writeChar ?: return
        val chunk = writeQueue.poll() ?: return
        writeInFlight = true
        ch.writeType = if (useNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.value = chunk
        val ok = runCatching { g.writeCharacteristic(ch) }.getOrDefault(false)
        // WRITE_TYPE_NO_RESPONSE still fires onCharacteristicWrite on most stacks;
        // if the initiate failed outright, don't stall the queue.
        if (!ok) {
            writeInFlight = false
            if (writeQueue.isNotEmpty()) pumpWrites(useNoResponse)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val g = gatt
        gatt = null
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
    }

    private fun deliver(bytes: ByteArray) {
        synchronized(preStartBuffer) {
            val cb = onData
            if (started && cb != null) cb(bytes) else preStartBuffer.add(bytes)
        }
    }

    private fun failReady(t: Throwable) {
        readyError = t
        ready.countDown()
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Larger MTU first (bigger writes/notifications); discovery
                    // follows in onMtuChanged, or here if the request can't start.
                    if (!runCatching { g.requestMtu(REQUEST_MTU) }.getOrDefault(false)) {
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (ready.count > 0L) {
                        failReady(IllegalStateException("BLE disconnected before ready (status $status)"))
                    } else if (!closed) {
                        onError?.invoke(IllegalStateException("BLE link dropped (status $status)"))
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > DEFAULT_MTU) mtuPayload = mtu - 3
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failReady(IllegalStateException("Service discovery failed (status $status)")); return
            }
            val resolved = resolveCharacteristics(g)
            if (resolved == null) {
                failReady(
                    IllegalStateException(
                        "No BLE-UART service on this device " +
                            "(tried Nordic UART + HM-10). If it uses custom UUIDs, they must be supplied.",
                    ),
                )
                return
            }
            writeChar = resolved.first
            notifyChar = resolved.second
            enableNotifications(g, resolved.second)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BleSerialParams.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready.countDown() // link is fully usable
            } else {
                failReady(IllegalStateException("Enabling notifications failed (status $status)"))
            }
        }

        // Pre-33: the framework calls this and populates ch.value.
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == notifyChar?.uuid) {
                val value = ch.value ?: return
                if (value.isNotEmpty()) deliver(value.copyOf())
            }
        }

        // API 33+: the framework calls this 3-arg form instead (its default impl
        // does NOT repopulate ch.value), so read the value param directly. We do
        // NOT call super here, so the 2-arg override never double-fires on 33+.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid == notifyChar?.uuid && value.isNotEmpty()) deliver(value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            synchronized(writeQueue) {
                writeInFlight = false
                val noResp = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                if (writeQueue.isNotEmpty()) pumpWrites(noResp)
            }
        }
    }

    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(BleSerialParams.CCCD)
        if (cccd == null) {
            failReady(IllegalStateException("Notify characteristic has no CCCD")); return
        }
        val indicate = (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
            (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
        cccd.value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (!runCatching { g.writeDescriptor(cccd) }.getOrDefault(false)) {
            failReady(IllegalStateException("Could not write CCCD to enable notifications"))
        }
    }

    /** @return (writeChar, notifyChar) or null if no known/explicit profile matched. */
    private fun resolveCharacteristics(
        g: BluetoothGatt,
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        // Explicit UUIDs win.
        if (params.serviceUuid != null && params.writeUuid != null && params.notifyUuid != null) {
            val svc: BluetoothGattService? = g.getService(params.serviceUuid)
            val w = svc?.getCharacteristic(params.writeUuid)
            val n = svc?.getCharacteristic(params.notifyUuid)
            return if (w != null && n != null) w to n else null
        }
        for (p in BleSerialParams.AUTO_PROFILES) {
            val svc = g.getService(p.service) ?: continue
            val w = svc.getCharacteristic(p.write) ?: continue
            val n = svc.getCharacteristic(p.notify) ?: continue
            Log.d(TAG, "matched ${p.name} on ${name ?: "device"}")
            return w to n
        }
        return null
    }

    private companion object {
        const val DEFAULT_MTU = 23
        const val REQUEST_MTU = 247
    }
}
