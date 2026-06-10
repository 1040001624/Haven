package sh.haven.feature.mail

import sh.haven.core.mail.MailClient
import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailMessage
import sh.haven.core.mail.OutgoingMail
import sh.haven.core.mail.SendResult

/**
 * The feature-layer view of a connected mail account: folders, message lists,
 * a fully-parsed (decrypted + MIME-parsed) message ready to render, and
 * outbound send. Backed by the engine-neutral [RfcMailBackend] (Proton and IMAP
 * both feed RFC822; send routes to IMAP/SMTP, Proton returns a 501 for now).
 */
interface MailBackend {
    suspend fun listFolders(): List<MailFolder>
    /** A page of envelopes, newest first: most-recent [limit], skipping [offset] from the top. */
    suspend fun listMessages(
        folderId: String,
        limit: Int = MailClient.DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): List<MailMessage>
    suspend fun readMessage(messageId: String): ParsedMessage
    /** Decode one attachment of [messageId] by its [index] (from [ParsedMessage.attachments]). */
    suspend fun readAttachment(messageId: String, index: Int): MimeParser.ExtractedAttachment
    suspend fun sendMessage(mail: OutgoingMail): SendResult

    /** Delete [messageId]. On Gmail this moves it to Trash (recoverable); plain IMAP is permanent. */
    suspend fun deleteMessage(messageId: String)
}

/** A decrypted, MIME-parsed message ready for the reader UI. */
data class ParsedMessage(
    val subject: String,
    val from: String,
    val to: List<String>,
    val dateMillis: Long?,
    /**
     * Best displayable text. v1 renders this as plain text only — no WebView,
     * so a message's remote images/scripts can never load (R5: no tracking-pixel
     * beacon, no tunnel-exit-IP leak, no JS). HTML-only messages are converted to
     * a stripped-text approximation; rich rendering in a locked-down WebView is a
     * later refinement.
     */
    val bodyText: String,
    /** True when [bodyText] was derived by stripping an HTML part (no plain part existed). */
    val bodyWasHtml: Boolean,
    val attachments: List<MailAttachmentInfo>,
)

data class MailAttachmentInfo(
    /**
     * Stable depth-first traversal index of this attachment part within the
     * message. Assigned in the same order [MimeParser] walks the MIME tree, so
     * [index] N always resolves to the same part — it is the key used by
     * [MimeParser.extractAttachment] to fetch this attachment's bytes on demand.
     */
    val index: Int,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    /**
     * True for an inline part (Content-Disposition: inline, or a part carrying a
     * Content-ID and no explicit attachment disposition) — typically a `cid:`
     * image referenced by an HTML body rather than a user-facing attachment.
     */
    val isInline: Boolean = false,
    /** The part's Content-ID with surrounding `<>` stripped, when present. */
    val contentId: String? = null,
)
