package sh.haven.feature.mail

import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.SingleBody
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.dom.address.Mailbox
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Parses the decrypted RFC822 MIME blob from the mailbridge into a [ParsedMessage].
 * Pure JVM (Apache Mime4j) so it is unit-testable off-device.
 *
 * v1 produces plain text only: the best text/plain part if present, otherwise a
 * tag-stripped approximation of the best text/html part. Nothing is ever handed
 * to a WebView, so remote content can never load (R5).
 *
 * Attachments are listed with a stable depth-first [MailAttachmentInfo.index];
 * the raw bytes for any one of them are fetched on demand with [extractAttachment].
 * The list-walk ([collect]) and the extract-walk ([walkExtract]) MUST share the
 * same [isAttachmentPart] predicate and traversal order so an index resolves to
 * the same part in both — the round-trip unit test guards this.
 */
object MimeParser {

    /** Decoded bytes for a single attachment, fetched by [extractAttachment]. */
    data class ExtractedAttachment(
        val filename: String,
        val mimeType: String,
        val bytes: ByteArray,
    )

    fun parse(rfc822: ByteArray): ParsedMessage {
        val message = buildMessage(rfc822)

        val plainParts = mutableListOf<String>()
        val htmlParts = mutableListOf<String>()
        val attachments = mutableListOf<MailAttachmentInfo>()
        collect(message, plainParts, htmlParts, attachments, intArrayOf(0))

        val plain = plainParts.maxByOrNull { it.length }
        val (body, wasHtml) = when {
            !plain.isNullOrBlank() -> plain to false
            else -> {
                val html = htmlParts.maxByOrNull { it.length }
                if (html != null) stripHtml(html) to true else "" to false
            }
        }

        return ParsedMessage(
            subject = message.subject ?: "(no subject)",
            from = message.from?.firstOrNull()?.let { displayMailbox(it) } ?: "",
            to = message.to?.flatten()?.map { displayMailbox(it) } ?: emptyList(),
            cc = message.cc?.flatten()?.map { displayMailbox(it) } ?: emptyList(),
            dateMillis = message.date?.time,
            bodyText = body,
            bodyWasHtml = wasHtml,
            attachments = attachments,
        )
    }

    /** Attachment metadata only (index/filename/mime/size/inline), no bytes. */
    fun listAttachments(rfc822: ByteArray): List<MailAttachmentInfo> = parse(rfc822).attachments

    /**
     * All top-level header fields as a lowercase-name → values map, for Mail-Rules
     * `header` criteria. Multi-valued headers (e.g. Received) keep every occurrence.
     */
    fun parseHeaders(rfc822: ByteArray): Map<String, List<String>> {
        val message = buildMessage(rfc822)
        val map = LinkedHashMap<String, MutableList<String>>()
        message.header?.fields?.forEach { f ->
            map.getOrPut(f.name.lowercase()) { mutableListOf() }.add(f.body ?: "")
        }
        return map
    }

    /**
     * Decode and return one attachment by its stable depth-first [index] (from
     * [parse]/[listAttachments]). Decodes the part's transfer-encoding
     * (base64/quoted-printable) and returns the raw file bytes.
     *
     * @throws IndexOutOfBoundsException if no attachment part has that index.
     */
    fun extractAttachment(rfc822: ByteArray, index: Int): ExtractedAttachment {
        val message = buildMessage(rfc822)
        val result = arrayOfNulls<ExtractedAttachment>(1)
        walkExtract(message, index, intArrayOf(0), result)
        return result[0] ?: throw IndexOutOfBoundsException("No attachment at index $index")
    }

    private fun buildMessage(rfc822: ByteArray): Message {
        val builder = DefaultMessageBuilder().apply { setMimeEntityConfig(MimeConfig.PERMISSIVE) }
        return ByteArrayInputStream(rfc822).use { builder.parseMessage(it) }
    }

    /**
     * A part is an attachment when it is a binary part (always), or a text part
     * explicitly marked as an attachment (disposition=attachment or has a
     * filename). Multipart containers are never attachments — we recurse into
     * them. Inline `cid:` images are binary parts and so are addressable too.
     */
    private fun isAttachmentPart(entity: Entity): Boolean = when (entity.body) {
        is Multipart -> false
        is TextBody -> entity.dispositionType?.equals("attachment", ignoreCase = true) == true ||
            !entity.filename.isNullOrBlank()
        else -> true // BinaryBody and anything else
    }

    private fun collect(
        entity: Entity,
        plain: MutableList<String>,
        html: MutableList<String>,
        attachments: MutableList<MailAttachmentInfo>,
        counter: IntArray,
    ) {
        when (val body = entity.body) {
            is Multipart -> body.bodyParts.forEach { collect(it, plain, html, attachments, counter) }
            else -> {
                if (isAttachmentPart(entity)) {
                    val idx = counter[0]++
                    attachments += MailAttachmentInfo(
                        index = idx,
                        filename = entity.filename ?: "attachment",
                        mimeType = entity.mimeType ?: defaultMime(body),
                        sizeBytes = (body as? SingleBody)?.let { bodySize(it) } ?: 0L,
                        isInline = isInline(entity),
                        contentId = contentId(entity),
                    )
                } else if (body is TextBody) {
                    when (entity.mimeType?.lowercase()) {
                        "text/html" -> html += body.reader.readText()
                        else -> plain += body.reader.readText() // text/plain and unknown text
                    }
                }
            }
        }
    }

    private fun walkExtract(
        entity: Entity,
        target: Int,
        counter: IntArray,
        result: Array<ExtractedAttachment?>,
    ) {
        when (val body = entity.body) {
            is Multipart -> body.bodyParts.forEach { walkExtract(it, target, counter, result) }
            else -> {
                if (isAttachmentPart(entity)) {
                    val idx = counter[0]++
                    if (idx == target && result[0] == null && body is SingleBody) {
                        val out = ByteArrayOutputStream()
                        body.writeTo(out)
                        result[0] = ExtractedAttachment(
                            filename = entity.filename ?: "attachment",
                            mimeType = entity.mimeType ?: defaultMime(body),
                            bytes = out.toByteArray(),
                        )
                    }
                }
            }
        }
    }

    private fun defaultMime(body: org.apache.james.mime4j.dom.Body): String =
        if (body is TextBody) "text/plain" else "application/octet-stream"

    /** Decoded byte length of a body, counted without buffering the whole thing. */
    private fun bodySize(body: SingleBody): Long {
        val counter = CountingOutputStream()
        body.writeTo(counter)
        return counter.count
    }

    private fun isInline(entity: Entity): Boolean {
        entity.dispositionType?.let {
            if (it.equals("inline", ignoreCase = true)) return true
            if (it.equals("attachment", ignoreCase = true)) return false
        }
        return contentId(entity) != null
    }

    private fun contentId(entity: Entity): String? =
        entity.header?.getField("Content-ID")?.body?.trim()
            ?.removeSurrounding("<", ">")
            ?.ifBlank { null }

    private fun displayMailbox(m: Mailbox): String =
        if (m.name.isNullOrBlank()) m.address else "${m.name} <${m.address}>"

    /** Crude but safe HTML→text: drop scripts/styles, strip tags, decode a few entities. */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private class CountingOutputStream : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
        }
    }
}
