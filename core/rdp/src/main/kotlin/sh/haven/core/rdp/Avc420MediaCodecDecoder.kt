package sh.haven.core.rdp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import sh.haven.rdp.Avc420Decoder
import java.io.Closeable
import java.nio.ByteBuffer

private const val TAG = "Avc420Decoder"

/**
 * MediaCodec-backed H.264/AVC420 decoder for EGFX tiles (#425, KRDP).
 *
 * The Rust session thread owns the framebuffer and decodes every other codec
 * inline, so [decode] is a **blocking** call: one Annex-B access unit in, one
 * `width*height*4` RGBA frame out (empty on failure → the caller drops the
 * tile). A single [MediaCodec] instance persists across calls so its reference
 * picture survives — KRDP sends SPS+PPS+IDR only in the first frame and
 * P-slices thereafter. KRDP's stream is Baseline profile (no B-frames, no
 * reordering), so the 1-in/1-out poll below is valid.
 *
 * ponytail: YUV→RGBA is a straight integer BT.601 loop on the session thread —
 * the known hotspot. Correct first; move to Rust/GL if device profiling shows
 * it caps the framerate. See #425.
 */
class Avc420MediaCodecDecoder : Avc420Decoder, Closeable {
    private var codec: MediaCodec? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var ptsUs = 0L
    @Volatile private var failed = false
    private val bufferInfo = MediaCodec.BufferInfo()
    // Reused RGBA scratch, grown as needed to avoid a per-frame allocation.
    private var rgba = ByteArray(0)

    override fun decode(annexB: ByteArray, width: UShort, height: UShort): ByteArray {
        val w = width.toInt()
        val h = height.toInt()
        if (failed || w <= 0 || h <= 0 || annexB.isEmpty()) return ByteArray(0)
        val mc = try {
            ensureCodec(annexB, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec init failed (${w}x${h}): ${e.message}")
            failed = true
            releaseCodec()
            return ByteArray(0)
        } ?: return ByteArray(0)

        return try {
            queueInput(mc, annexB)
            drainToRgba(mc, w, h) ?: ByteArray(0)
        } catch (e: Exception) {
            Log.e(TAG, "AVC420 decode failed: ${e.message}")
            failed = true
            releaseCodec()
            ByteArray(0)
        }
    }

    /** Configure the decoder on the first frame using its in-band SPS/PPS. */
    private fun ensureCodec(annexB: ByteArray, w: Int, h: Int): MediaCodec? {
        codec?.let { return it }
        val sps = findNal(annexB, 7)
        val pps = findNal(annexB, 8)
        if (sps == null || pps == null) {
            // First AU must carry the parameter sets; a P-slice can't configure.
            Log.e(TAG, "first AVC420 AU lacks SPS/PPS — cannot configure")
            failed = true
            return null
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // Ask for a byte-buffer output format we can read as planar YUV.
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mc.configure(format, /* surface = */ null, /* crypto = */ null, /* flags = */ 0)
        mc.start()
        codec = mc
        configuredWidth = w
        configuredHeight = h
        Log.d(TAG, "AVC420 MediaCodec configured ${w}x${h} (${mc.codecInfo.name})")
        return mc
    }

    private fun queueInput(mc: MediaCodec, annexB: ByteArray) {
        // Bounded wait for an input buffer; the decoder should always have one
        // for a low-latency Baseline stream.
        val inIx = mc.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inIx < 0) throw IllegalStateException("no input buffer available")
        val inBuf = mc.getInputBuffer(inIx) ?: throw IllegalStateException("null input buffer")
        inBuf.clear()
        inBuf.put(annexB)
        mc.queueInputBuffer(inIx, 0, annexB.size, ptsUs, 0)
        ptsUs += FRAME_INTERVAL_US
    }

    /** Poll for the single output frame for the AU just queued. */
    private fun drainToRgba(mc: MediaCodec, w: Int, h: Int): ByteArray? {
        var polls = 0
        while (polls++ < MAX_OUTPUT_POLLS) {
            val outIx = mc.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                outIx >= 0 -> {
                    val out = try {
                        val image = mc.getOutputImage(outIx)
                        if (image != null) yuvImageToRgba(image, w, h) else null
                    } finally {
                        mc.releaseOutputBuffer(outIx, /* render = */ false)
                    }
                    if (out != null) return out
                    // No image (config-only) — keep polling.
                }
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "AVC420 output format: ${mc.outputFormat}")
                }
                outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Decoder still working on this AU; retry within budget.
                }
                else -> { /* INFO_OUTPUT_BUFFERS_CHANGED (deprecated) — ignore */ }
            }
        }
        Log.w(TAG, "AVC420 decode produced no frame within poll budget")
        return null
    }

    /**
     * Convert a YUV_420_888 [android.media.Image] to tightly-packed RGBA8888
     * cropped to [w]x[h], BT.601 limited range. Handles both planar (I420) and
     * semi-planar (NV12/NV21) chroma via each plane's row/pixel stride.
     */
    private fun yuvImageToRgba(image: android.media.Image, w: Int, h: Int, ): ByteArray {
        val cw = minOf(w, image.width)
        val ch = minOf(h, image.height)
        val need = w * h * 4
        if (rgba.size < need) rgba = ByteArray(need)
        val out = rgba

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride

        var o = 0
        for (y in 0 until h) {
            val sy = if (y < ch) y else ch - 1
            val yLine = sy * yRow
            val cLine = (sy shr 1)
            val uLine = cLine * uRow
            val vLine = cLine * vRow
            for (x in 0 until w) {
                val sx = if (x < cw) x else cw - 1
                val yv = (yBuf.get(yLine + sx).toInt() and 0xFF) - 16
                val cx = sx shr 1
                val uv = (uBuf.get(uLine + cx * uPix).toInt() and 0xFF) - 128
                val vv = (vBuf.get(vLine + cx * vPix).toInt() and 0xFF) - 128
                val c = if (yv < 0) 0 else yv * 298
                val r = (c + 409 * vv + 128) shr 8
                val g = (c - 100 * uv - 208 * vv + 128) shr 8
                val b = (c + 516 * uv + 128) shr 8
                out[o] = clamp(r)
                out[o + 1] = clamp(g)
                out[o + 2] = clamp(b)
                out[o + 3] = 0xFF.toByte()
                o += 4
            }
        }
        // ByteArray of exactly need bytes for the UniFFI Vec<u8>.
        return if (out.size == need) out.copyOf() else out.copyOf(need)
    }

    private fun clamp(v: Int): Byte = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()

    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }

    override fun close() {
        releaseCodec()
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 20_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val MAX_OUTPUT_POLLS = 8
        // Nominal 60 fps spacing; PTS ordering only, value is otherwise unused
        // for a no-reorder Baseline stream.
        const val FRAME_INTERVAL_US = 16_666L

        /**
         * Return the first Annex-B NAL (including its start code) whose
         * nal_unit_type == [type], or null. Scans for 3- and 4-byte start codes.
         */
        fun findNal(data: ByteArray, type: Int): ByteArray? {
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 3 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (data[i + 2] == 1.toByte()) {
                        starts.add(i); i += 3; continue
                    }
                    if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        starts.add(i); i += 4; continue
                    }
                }
                i++
            }
            for ((k, s) in starts.withIndex()) {
                // NAL header byte follows the start code (00 00 01 or 00 00 00 01).
                val hdr = if (s + 3 < data.size && data[s + 2] == 1.toByte()) s + 3 else s + 4
                if (hdr >= data.size) continue
                if ((data[hdr].toInt() and 0x1F) == type) {
                    val end = if (k + 1 < starts.size) starts[k + 1] else data.size
                    return data.copyOfRange(s, end)
                }
            }
            return null
        }
    }
}
