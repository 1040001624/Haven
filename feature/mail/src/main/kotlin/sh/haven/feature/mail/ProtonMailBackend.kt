package sh.haven.feature.mail

import sh.haven.core.mail.MailClient
import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailMessage

/**
 * [MailBackend] over a connected Proton session: list calls delegate straight to
 * the [MailClient]; [readMessage] fetches the decrypted RFC822 bytes and parses
 * them with [MimeParser].
 */
class ProtonMailBackend(
    private val client: MailClient,
    private val sessionId: String,
) : MailBackend {

    override suspend fun listFolders(): List<MailFolder> = client.listFolders(sessionId)

    override suspend fun listMessages(folderId: String): List<MailMessage> =
        client.listMessages(sessionId, folderId, desc = true)

    override suspend fun readMessage(messageId: String): ParsedMessage {
        val raw = client.getMessageRaw(sessionId, messageId)
        return MimeParser.parse(raw)
    }
}
