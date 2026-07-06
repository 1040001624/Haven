package sh.haven.core.vnc

import android.graphics.Bitmap
import java.security.cert.X509Certificate

/**
 * Configuration for a VNC client connection.
 */
class VncConfig {
    var passwordSupplier: (() -> String)? = null
    var usernameSupplier: (() -> String)? = null

    /**
     * Called during a VeNCrypt X509 handshake with the server's leaf
     * certificate. Implementations perform trust-on-first-use pinning and
     * MUST throw to abort the connection if the certificate is not trusted
     * (e.g. it changed from a previously pinned one — a possible MITM).
     * When null, the X509 certificate is not validated (legacy behaviour).
     * Fixes security-review critical #1.
     */
    var verifyServerCert: ((X509Certificate) -> Unit)? = null

    /**
     * Fired when the negotiated security is encrypted-but-unauthenticated
     * (anonymous-DH VeNCrypt TLS, which carries no certificate) or entirely
     * unauthenticated (security type None). The UI surfaces this as a
     * non-blocking banner so the user knows the server identity is unverified.
     */
    var onSecurityWarning: ((String) -> Unit)? = null
    var onScreenUpdate: ((Bitmap) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null
    var onBell: (() -> Unit)? = null
    var onRemoteClipboard: ((String) -> Unit)? = null
    /**
     * Called when the server sends a new cursor shape via the Cursor
     * pseudo-encoding. Args: ARGB_8888 bitmap, hotspot x, hotspot y.
     * An empty cursor (0×0) is signalled as a null bitmap and hotspot 0,0.
     */
    var onCursorUpdate: ((Bitmap?, Int, Int) -> Unit)? = null

    /**
     * Fired once per session when measured throughput is sustained below
     * the recommend-downshift threshold (currently <1 Mbps over a 10 s
     * window) AND the current colour depth is higher than the suggestion.
     * Argument is the suggested [ColorDepth] (always
     * [ColorDepth.BPP_8_INDEXED] in v1). The UI shows a non-blocking
     * banner; a clean reconnect at the new depth happens only on user
     * confirmation.
     */
    var onBandwidthSuggestion: ((ColorDepth) -> Unit)? = null
    var shared: Boolean = true
    var targetFps: Int = 30
    var colorDepth: ColorDepth = ColorDepth.BPP_24_TRUE
}

enum class ColorDepth(
    val bitsPerPixel: Int,
    val depth: Int,
    val trueColor: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int,
) {
    BPP_8_INDEXED(8, 8, false, 0, 0, 0, 0, 0, 0),
    BPP_8_TRUE(8, 8, true, 7, 3, 7, 0, 6, 3),
    BPP_16_TRUE(16, 16, true, 31, 63, 31, 11, 5, 0),
    BPP_24_TRUE(32, 24, true, 255, 255, 255, 16, 8, 0),
}
