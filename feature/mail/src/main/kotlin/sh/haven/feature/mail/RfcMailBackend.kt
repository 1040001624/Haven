package sh.haven.feature.mail

import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.mail.MailClient
import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailMessage
import sh.haven.core.mail.OutgoingMail
import sh.haven.core.mail.SendResult

/**
 * Engine-neutral [MailBackend] over any connected [MailClient]: list calls
 * delegate straight through, and [readMessage] fetches the raw RFC822 bytes and
 * parses them with [MimeParser]. Both engines converge here — Proton decrypts to
 * RFC822 in the Go bridge, IMAP emits RFC822 via `message.writeTo` — so a single
 * backend (and a single parser) serves every provider.
 */
class RfcMailBackend(
    private val client: MailClient,
    private val sessionId: String,
    private val profileId: String,
    private val connectionLog: ConnectionLogRepository,
) : MailBackend {

    override suspend fun listFolders(): List<MailFolder> = client.listFolders(sessionId)

    override suspend fun listMessages(folderId: String, limit: Int, offset: Int): List<MailMessage> =
        client.listMessages(sessionId, folderId, desc = true, limit = limit, offset = offset)

    override suspend fun readMessage(messageId: String): ParsedMessage {
        val raw = client.getMessageRaw(sessionId, messageId)
        return MimeParser.parse(raw)
    }

    override suspend fun sendMessage(mail: OutgoingMail): SendResult {
        val result = client.send(sessionId, mail)
        // Audit-log a successful send so it shows in Settings → connection log,
        // matching the send_mail MCP tool's row. Logging must never fail the send.
        runCatching {
            connectionLog.logEvent(
                profileId,
                ConnectionLog.Status.CONNECTED,
                details = "Sent mail to ${mail.to.size} recipient(s)" +
                    (if (mail.subject.isNotBlank()) " — \"${mail.subject}\"" else ""),
            )
        }
        return result
    }
}
