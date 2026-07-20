package sh.haven.core.rdp

import android.graphics.Bitmap
import android.util.Log
import sh.haven.rdp.FrameCallback
import sh.haven.rdp.FrameData
import sh.haven.rdp.MouseButton
import sh.haven.rdp.PointerCallback
import sh.haven.rdp.RdpClient
import sh.haven.rdp.RdpConfig
import sh.haven.rdp.RdpException
import sh.haven.rdp.SessionCallback
import sh.haven.rdp.SocksProxyConfig
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "RdpSession"

/**
 * Wraps an [RdpClient] (IronRDP via UniFFI) with Android-specific
 * bitmap management and lifecycle.
 *
 * Similar pattern to VncClient but adapted for RDP:
 * - RDP uses scancodes, not X11 KeySyms
 * - Frame delivery via polling getFramebuffer() + callback for dirty rects
 * - No mid-session resize (RDP requires reconnect)
 */
class RdpSession(
    val sessionId: String,
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val domain: String = "",
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val useNla: Boolean = true,
    private val colorDepth: Int = 16,
    private val onDisconnected: (() -> Unit)? = null,
    private val verboseBuffer: ConcurrentLinkedQueue<String>? = null,
    /**
     * Optional SOCKS5 endpoint for routing IronRDP's TCP through a
     * userspace tunnel — typically the WireGuard / Tailscale tunnel's
     * 127.0.0.1 listener (#149). Null = direct kernel dial.
     */
    private val socksProxy: SocksProxyConfig? = null,
    /**
     * TOFU verifier for the server's TLS certificate (security-review #2). The
     * pinned fingerprint is looked up before connect and a changed cert is
     * rejected inside the native handshake before credentials are sent; the
     * observed cert is pinned on first use. Null disables pinning.
     */
    private val tlsCertVerifier: sh.haven.core.data.agent.TlsCertVerifier? = null,
    /**
     * Stable server identity for the cert pin, independent of the socket
     * target. For an SSH-tunnelled session [host] is 127.0.0.1, so the caller
     * passes the real remote here; defaults to [host]:[port] for direct dials.
     */
    private val certHost: String = host,
    private val certPort: Int = port,
) : Closeable {

    @Volatile
    private var closed = false
    private var client: RdpClient? = null
    private var currentBitmap: Bitmap? = null
    private val startTime = System.currentTimeMillis()

    private fun log(level: String, msg: String) {
        if (level == "E") Log.e(TAG, msg) else Log.d(TAG, msg)
        verboseBuffer?.add("+${System.currentTimeMillis() - startTime}ms [$TAG] $level: $msg")
    }

    /** Called on frame updates. Set by the ViewModel. */
    var onFrameUpdate: ((Bitmap) -> Unit)? = null

    /**
     * Called when the server pushes a new cursor shape / visibility (#212).
     * Args mirror VncConfig.onCursorUpdate: (cursor bitmap or null to hide,
     * hotspot x, hotspot y). The app layer wraps this into a CursorOverlay.
     */
    var onCursorUpdate: ((Bitmap?, Int, Int) -> Unit)? = null

    /**
     * Called when the server moves the pointer (DIRECT mode). Touchpad mode
     * drives the cursor position client-side, so this is advisory.
     */
    var onCursorPosition: ((Int, Int) -> Unit)? = null

    /** Last non-null cursor shape, re-emitted on PointerDefault. */
    private var lastCursor: Triple<Bitmap, Int, Int>? = null

    /** Called when an error occurs. */
    var onError: ((Exception) -> Unit)? = null

    /**
     * Called once the RDP handshake + capability exchange completes. Prior to
     * this the session is still in the "Connecting" phase even though
     * [start] has returned — the Rust connect() only spawns the worker
     * thread. The UI uses this to flip from "Connecting…" to the rendered
     * framebuffer.
     */
    var onConnected: ((Int, Int) -> Unit)? = null

    /**
     * Start the RDP session. Call from `Dispatchers.IO`.
     *
     * Returns once the TCP connection is established and the worker thread
     * is spawned — this is NOT equivalent to a usable RDP session. The
     * handshake, TLS upgrade and capability exchange happen asynchronously
     * on the worker thread; [onConnected] fires when it completes, [onError]
     * fires if it fails.
     */
    fun start() {
        if (closed) return
        log("D", "Starting RDP session $sessionId: $host:$port user=$username")

        try {
            // Trust-on-first-use pin: the fingerprint remembered from a prior
            // connection, or null on first use. A short blocking DB read.
            val pinnedCert = tlsCertVerifier?.let { v ->
                kotlinx.coroutines.runBlocking { v.pinnedFingerprint(certHost, certPort) }
            }
            val config = RdpConfig(
                username = username,
                password = password,
                domain = domain,
                width = width.toUShort(),
                height = height.toUShort(),
                // Per-profile colour depth (#109). Default 16 is xrdp-
                // safe; user can switch to 32 for Windows servers to get
                // RemoteFX-driven smooth updates. Picker lives in the
                // RDP block of ConnectionEditDialog.
                colorDepth = colorDepth.toUByte(),
                enableCredssp = useNla,
                pinnedCertSha256 = pinnedCert,
                // #418 debug opt-in: WBT_TILE_UPGRADE refinement decoding.
                // Bridged from Settings → Diagnostics via RdpDebugToggles.
                progressiveUpgrade = RdpDebugToggles.progressiveUpgrade,
            )

            val c = RdpClient(config)
            client = c

            c.setFrameCallback(object : FrameCallback {
                override fun onFrameUpdate(x: UShort, y: UShort, w: UShort, h: UShort) {
                    if (closed) return
                    try {
                        refreshBitmap()
                    } catch (e: Exception) {
                        log("E", "Frame update failed (${x},${y} ${w}x${h}): ${e.message}")
                        onError?.invoke(e)
                    }
                }

                override fun onResize(width: UShort, height: UShort) {
                    if (closed) return
                    log("D", "Desktop resized: ${width}x${height}")
                    try {
                        synchronized(this@RdpSession) {
                            currentBitmap?.recycle()
                            currentBitmap = null
                        }
                        refreshBitmap()
                    } catch (e: Exception) {
                        log("E", "Resize failed (${width}x${height}): ${e.message}")
                        onError?.invoke(e)
                    }
                }
            })

            c.setSessionCallback(object : SessionCallback {
                override fun onConnected(width: UShort, height: UShort) {
                    if (closed) return
                    log("D", "RDP handshake complete: ${width}x${height}")
                    onConnected?.invoke(width.toInt(), height.toInt())
                }

                override fun onError(message: String) {
                    if (closed) return
                    log("E", "RDP session error: $message")
                    this@RdpSession.onError?.invoke(RuntimeException(message))
                }

                override fun onDisconnected() {
                    if (closed) return
                    log("D", "RDP session ended cleanly")
                    onDisconnected?.invoke()
                }

                override fun onServerCert(sha256: String) {
                    // First-use TOFU pin (a changed cert was already rejected
                    // in the native handshake, so this only fires on match or
                    // first connect). Idempotent for an already-pinned cert.
                    val v = tlsCertVerifier ?: return
                    try {
                        kotlinx.coroutines.runBlocking { v.accept(certHost, certPort, sha256) }
                        log("D", "Pinned RDP TLS cert for $certHost:$certPort: ${sha256.take(16)}…")
                    } catch (e: Exception) {
                        log("E", "Failed to pin RDP TLS cert: ${e.message}")
                    }
                }
            })

            c.setPointerCallback(object : PointerCallback {
                override fun onPointerBitmap(
                    width: UShort,
                    height: UShort,
                    hotspotX: UShort,
                    hotspotY: UShort,
                    rgba: ByteArray,
                ) {
                    if (closed) return
                    val w = width.toInt()
                    val h = height.toInt()
                    if (w <= 0 || h <= 0 || rgba.size < w * h * 4) {
                        log("E", "Bad pointer bitmap ${w}x$h, ${rgba.size} bytes")
                        return
                    }
                    try {
                        val bmp = pointerToBitmap(rgba, w, h)
                        lastCursor = Triple(bmp, hotspotX.toInt(), hotspotY.toInt())
                        onCursorUpdate?.invoke(bmp, hotspotX.toInt(), hotspotY.toInt())
                    } catch (e: Exception) {
                        log("E", "pointerToBitmap failed (${w}x$h): ${e.message}")
                    }
                }

                override fun onPointerHidden() {
                    if (closed) return
                    // Temporary hide (video/games) — drop the overlay but keep
                    // lastCursor so a subsequent PointerDefault can restore it.
                    onCursorUpdate?.invoke(null, 0, 0)
                }

                override fun onPointerDefault() {
                    if (closed) return
                    lastCursor?.let { (bmp, hx, hy) -> onCursorUpdate?.invoke(bmp, hx, hy) }
                }

                override fun onPointerPosition(x: UShort, y: UShort) {
                    if (closed) return
                    onCursorPosition?.invoke(x.toInt(), y.toInt())
                }
            })

            log("D", "Connecting to $host:$port (worker thread will handle handshake, socks=${socksProxy != null})")
            c.connect(host, port.toUShort(), socksProxy)
        } catch (e: UnsatisfiedLinkError) {
            val msg = "RDP native library failed to load: ${e.message}"
            log("E", msg)
            val wrapped = RuntimeException(msg, e)
            onError?.invoke(wrapped)
            onDisconnected?.invoke()
            throw wrapped
        } catch (e: Exception) {
            log("E", "RDP connect dispatch failed: ${e.message}")
            onError?.invoke(e)
            onDisconnected?.invoke()
            throw e
        }
    }

    private fun refreshBitmap() {
        val c = client ?: return
        val frame = try {
            c.getFramebuffer() ?: return
        } catch (e: Exception) {
            log("E", "getFramebuffer() failed: ${e.message}")
            onError?.invoke(e)
            return
        }
        val bitmap = try {
            frameToBitmap(frame)
        } catch (e: Exception) {
            log("E", "frameToBitmap() failed (${frame.width}x${frame.height}, ${frame.pixels.size} bytes): ${e.message}")
            onError?.invoke(e)
            return
        }
        synchronized(this) {
            currentBitmap = bitmap
        }
        onFrameUpdate?.invoke(bitmap)
    }

    /**
     * Convert FrameData (ARGB_8888 byte array) to Android Bitmap.
     */
    private fun frameToBitmap(frame: FrameData): Bitmap {
        val w = frame.width.toInt()
        val h = frame.height.toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.pixels))
        return bitmap
    }

    /**
     * Convert an IronRDP DecodedPointer bitmap (RGBA bytes, non-premultiplied
     * alpha — `pointer_software_rendering` is off) to an Android Bitmap. Packs
     * each RGBA pixel into an ARGB colour int; `createBitmap(IntArray, …)`
     * treats the input as non-premultiplied and stores it premultiplied, so the
     * Compose overlay alpha-blends correctly. Mirrors the VNC cursor path
     * (Framebuffer.renderCursor).
     */
    private fun pointerToBitmap(rgba: ByteArray, w: Int, h: Int): Bitmap {
        val argb = IntArray(w * h)
        for (i in 0 until w * h) {
            val o = i * 4
            val r = rgba[o].toInt() and 0xFF
            val g = rgba[o + 1].toInt() and 0xFF
            val b = rgba[o + 2].toInt() and 0xFF
            val a = rgba[o + 3].toInt() and 0xFF
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Get the current frame as a Bitmap. */
    fun getFrame(): Bitmap? = synchronized(this) { currentBitmap }

    // --- Input forwarding ---

    fun sendKey(scancode: Int, pressed: Boolean) {
        if (closed) return
        client?.sendKey(scancode.toUShort(), pressed)
    }

    fun sendUnicodeKey(codepoint: Int, pressed: Boolean) {
        if (closed) return
        client?.sendUnicodeKey(codepoint.toUInt(), pressed)
    }

    fun sendMouseMove(x: Int, y: Int) {
        if (closed) return
        client?.sendMouseMove(x.toUShort(), y.toUShort())
    }

    fun sendMouseButton(button: MouseButton, pressed: Boolean) {
        if (closed) return
        client?.sendMouseButton(button, pressed)
    }

    fun sendMouseClick(x: Int, y: Int, button: MouseButton = MouseButton.LEFT) {
        if (closed) return
        client?.sendMouseMove(x.toUShort(), y.toUShort())
        client?.sendMouseButton(button, true)
        client?.sendMouseButton(button, false)
    }

    fun sendMouseWheel(vertical: Boolean, delta: Int) {
        if (closed) return
        client?.sendMouseWheel(vertical, delta.toShort())
    }

    fun sendClipboardText(text: String) {
        if (closed) return
        client?.sendClipboardText(text)
    }

    /** Drain captured verbose logs. Returns null if verbose logging was not enabled. */
    fun drainVerboseLog(): String? {
        val buf = verboseBuffer ?: return null
        if (buf.isEmpty()) return null
        val sb = StringBuilder()
        while (true) {
            val line = buf.poll() ?: break
            sb.appendLine(line)
        }
        return sb.toString().trimEnd()
    }

    override fun close() {
        if (closed) return
        closed = true
        log("D", "Closing RDP session $sessionId")
        try {
            client?.disconnect()
        } catch (e: Exception) {
            log("E", "Error disconnecting RDP: ${e.message}")
        }
        client = null
        synchronized(this) {
            currentBitmap?.recycle()
            currentBitmap = null
        }
    }
}
