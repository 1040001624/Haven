package sh.haven.core.local.proot

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL

// #284: cleartext-import fallback (see downloadCleartextFallback).
private const val CLEARTEXT_CONNECT_TIMEOUT_MS = 10_000
private const val CLEARTEXT_READ_TIMEOUT_MS = 30_000
private const val MAX_CLEARTEXT_BYTES = 4L * 1024 * 1024 * 1024 // 4 GB — generous for a rootfs tarball

/** Ordinary platform download (HttpURLConnection) — subject to the app's cleartext policy. */
fun downloadViaPlatform(url: String, dest: File, onProgress: (Int) -> Unit) {
    val conn = URL(url).openConnection()
    val totalSize = conn.contentLength
    BufferedInputStream(conn.getInputStream()).use { input ->
        FileOutputStream(dest).use { output ->
            val buf = ByteArray(8192)
            var downloaded = 0L
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                downloaded += n
                if (totalSize > 0) onProgress((downloaded * 100 / totalSize).toInt())
            }
        }
    }
}

/**
 * Minimal raw-socket HTTP/1.1 GET, used ONLY as the #284 cleartext fallback
 * when importing a custom rootfs. A [Socket] isn't intercepted by Android's
 * `NetworkSecurityConfig` cleartext check (that check lives in
 * `HttpURLConnection`/OkHttp's connection setup, not in `Socket` itself),
 * which is what lets this reach a host the declarative XML config can't
 * express — the config only allowlists literal domains, and a self-hosted
 * LAN mirror's IP varies per user/network.
 *
 * Deliberately narrow: no connection pooling, no cookies, no compression
 * negotiation, no HTTPS (http:// callers only — the ProotManager call site
 * only reaches this after a plain http:// URL hit the platform's cleartext
 * block) — just enough HTTP/1.1 to fetch one file from a plain static file
 * server (the "http mirror serving a tarball" case this exists for). Follows
 * up to 3 redirects, supports Content-Length and chunked transfer-encoding,
 * and caps the response at [MAX_CLEARTEXT_BYTES] so a misbehaving/malicious
 * server can't fill the device's storage.
 */
fun downloadCleartextFallback(url: String, dest: File, onProgress: (Int) -> Unit) {
    var current = URI(url)
    repeat(4) { _ ->
        val host = current.host ?: throw IOException("invalid URL: $current")
        val port = if (current.port > 0) current.port else 80
        val path = current.rawPath.ifEmpty { "/" } + (current.rawQuery?.let { "?$it" } ?: "")
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), CLEARTEXT_CONNECT_TIMEOUT_MS)
            sock.soTimeout = CLEARTEXT_READ_TIMEOUT_MS
            sock.getOutputStream().apply {
                write(
                    ("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n" +
                        "User-Agent: Haven\r\nAccept: */*\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                flush()
            }
            val input = BufferedInputStream(sock.getInputStream())
            val statusLine = readHttpLine(input) ?: throw IOException("empty response from $host")
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IOException("malformed status line: $statusLine")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            if (status in 300..399) {
                val location = headers["location"] ?: throw IOException("HTTP $status with no Location")
                current = current.resolve(location)
                return@use // retry the outer loop against the redirect target
            }
            if (status !in 200..299) throw IOException("HTTP $status from $host")

            val contentLength = headers["content-length"]?.toLongOrNull()
            val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
            FileOutputStream(dest).use { out ->
                var total = 0L
                fun writeChunk(buf: ByteArray, n: Int) {
                    out.write(buf, 0, n); total += n
                    if (total > MAX_CLEARTEXT_BYTES) throw IOException("download exceeds ${MAX_CLEARTEXT_BYTES / (1 shl 20)} MB")
                    if (contentLength != null && contentLength > 0) onProgress((total * 100 / contentLength).toInt())
                }
                if (chunked) {
                    val buf = ByteArray(8192)
                    while (true) {
                        val sizeLine = readHttpLine(input) ?: break
                        val chunkSize = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                            ?: throw IOException("malformed chunk size: $sizeLine")
                        if (chunkSize == 0L) { readHttpLine(input); break }
                        var remaining = chunkSize
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) throw IOException("truncated chunk body")
                            writeChunk(buf, n)
                            remaining -= n
                        }
                        readHttpLine(input) // trailing CRLF after each chunk's data
                    }
                } else {
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) writeChunk(buf, n)
                }
            }
            return // success — done with the whole function
        }
    }
    throw IOException("too many redirects fetching $url")
}

/** Read one CRLF- or LF-terminated header/status line (bounded — refuses a runaway header). */
private fun readHttpLine(input: InputStream): String? {
    val line = StringBuilder()
    var sawByte = false
    while (line.length < 8192) {
        val b = input.read()
        if (b == -1) return if (sawByte) line.toString() else null
        sawByte = true
        if (b == '\n'.code) {
            if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
            return line.toString()
        }
        line.append(b.toChar())
    }
    throw IOException("HTTP header line exceeded 8 KB")
}
