package sh.haven.feature.mail

import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.dom.address.Mailbox
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig
import java.io.ByteArrayInputStream

/**
 * Parses the decrypted RFC822 MIME blob from the mailbridge into a [ParsedMessage].
 * Pure JVM (Apache Mime4j) so it is unit-testable off-device.
 *
 * v1 produces plain text only: the best text/plain part if present, otherwise a
 * tag-stripped approximation of the best text/html part. Nothing is ever handed
 * to a WebView, so remote content can never load (R5).
 */
object MimeParser {

    fun parse(rfc822: ByteArray): ParsedMessage {
        val builder = DefaultMessageBuilder().apply {
            setMimeEntityConfig(MimeConfig.PERMISSIVE)
        }
        val message: Message = ByteArrayInputStream(rfc822).use { builder.parseMessage(it) }

        val plainParts = mutableListOf<String>()
        val htmlParts = mutableListOf<String>()
        val attachments = mutableListOf<MailAttachmentInfo>()
        collect(message, plainParts, htmlParts, attachments)

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
            dateMillis = message.date?.time,
            bodyText = body,
            bodyWasHtml = wasHtml,
            attachments = attachments,
        )
    }

    private fun collect(
        entity: Entity,
        plain: MutableList<String>,
        html: MutableList<String>,
        attachments: MutableList<MailAttachmentInfo>,
    ) {
        val isAttachment = entity.dispositionType?.equals("attachment", ignoreCase = true) == true ||
            !entity.filename.isNullOrBlank()
        when (val body = entity.body) {
            is Multipart -> body.bodyParts.forEach { collect(it, plain, html, attachments) }
            is TextBody -> {
                if (isAttachment) {
                    attachments += MailAttachmentInfo(
                        filename = entity.filename ?: "attachment",
                        mimeType = entity.mimeType ?: "text/plain",
                        sizeBytes = 0L,
                    )
                } else when (entity.mimeType?.lowercase()) {
                    "text/html" -> html += body.reader.readText()
                    else -> plain += body.reader.readText() // text/plain and unknown text
                }
            }
            else -> {
                // BinaryBody (images, files) — record as an attachment by name.
                attachments += MailAttachmentInfo(
                    filename = entity.filename ?: "attachment",
                    mimeType = entity.mimeType ?: "application/octet-stream",
                    sizeBytes = 0L,
                )
            }
        }
    }

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
}
