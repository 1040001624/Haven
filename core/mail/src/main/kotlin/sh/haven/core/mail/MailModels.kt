package sh.haven.core.mail

/**
 * Mail domain models shared across the engine (Proton Go bridge in v1; JVM
 * Jakarta Mail in stage 2) and the UI. Kept deliberately small — message
 * bodies are parsed from RFC822 in the feature layer, not here.
 */

/**
 * Engine-neutral special-use classification for a mailbox, derived from IMAP RFC 6154
 * attributes (`\Sent`, `\Trash`, …) or Proton's system-folder ids. The UI uses it to pick
 * an icon and to order the folder list (Inbox first, user labels last). [sortOrder] is the
 * list position; lower sorts earlier.
 */
enum class MailFolderRole(val sortOrder: Int) {
    INBOX(0),
    STARRED(1),
    IMPORTANT(2),
    SENT(3),
    DRAFTS(4),
    ARCHIVE(5),
    SPAM(6),
    TRASH(7),
    NONE(8),
}

/**
 * A mailbox folder / Proton label. [type] is Proton's LabelType (1=label, 2=folder, 3=system).
 * [role] is the engine-neutral special-use class (for UI icons + ordering). [selectable] is
 * false for IMAP `\Noselect` container mailboxes (e.g. Gmail's `[Gmail]` parent) that hold no
 * messages and must not be opened.
 */
data class MailFolder(
    val id: String,
    val name: String,
    val type: Int,
    val color: String? = null,
    val parentId: String? = null,
    val role: MailFolderRole = MailFolderRole.NONE,
    val selectable: Boolean = true,
) {
    /** Proton system folders carry well-known stable ids ("0"); IMAP's inbox is the fullName "INBOX". */
    val isInbox: Boolean get() = role == MailFolderRole.INBOX || id == INBOX_ID || id.equals("INBOX", ignoreCase = true)

    companion object {
        const val INBOX_ID = "0"
        const val ALL_MAIL_ID = "5"
        const val SENT_ID = "7"
        const val DRAFTS_ID = "8"
        const val TRASH_ID = "3"
        const val SPAM_ID = "4"
        const val ARCHIVE_ID = "6"
        const val STARRED_ID = "10"
        const val TYPE_SYSTEM = 3
    }
}

/** An email address with an optional display name. */
data class MailAddress(
    val name: String,
    val address: String,
) {
    /** "Alice <alice@example.com>" or just the address when unnamed. */
    fun display(): String = if (name.isBlank()) address else "$name <$address>"
}

/**
 * Message envelope metadata (the message-list row). The decrypted body is
 * fetched separately via [MailClient.getMessageRaw] and parsed in the feature
 * layer; this model intentionally omits it.
 */
data class MailMessage(
    val id: String,
    val subject: String,
    val from: MailAddress?,
    val to: List<MailAddress> = emptyList(),
    val unread: Boolean = false,
    /** Server timestamp, unix epoch seconds. */
    val timeSeconds: Long = 0L,
    val numAttachments: Int = 0,
)

/**
 * A folder's IMAP UID state, read at the start of a Mail-Rules poll. [maxUid] is the
 * highest existing UID (0 when empty); [uidValidity] guards against the server
 * renumbering the mailbox; [uidNext] is the advertised next-UID (diagnostic).
 */
data class MailFolderUidState(
    val uidValidity: Long,
    val uidNext: Long?,
    val maxUid: Long,
)

/** A message returned by the new-mail poll: its envelope plus its IMAP [uid]. */
data class MailNewMessage(
    val message: MailMessage,
    val uid: Long,
)

/**
 * An outgoing message handed to [MailClient.send]. Carries a plain-text body and
 * optional [attachments]; HTML bodies are still deferred. [to] must be non-empty;
 * the From address is the authenticated account and is set by the engine, not the
 * caller. [inReplyToMessageId] is the opaque id (from list_mail_messages) of the
 * message being replied to — when set, the engine resolves its RFC `Message-ID`
 * and `References` and sets `In-Reply-To` / `References` so the reply threads.
 */
data class OutgoingMail(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val bodyText: String,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val inReplyToMessageId: String? = null,
)

/**
 * A fully-resolved outbound attachment: the bytes are already in hand. The
 * feature/app layer resolves the source (a Haven backend path or a device URI)
 * to bytes BEFORE calling [MailClient.send], so this domain model — and the JVM
 * SMTP engine that consumes it — stays free of Android and FileBackend types.
 * Identity (equals/hashCode) is by reference for [bytes]; these are never compared.
 */
data class OutgoingAttachment(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Outcome of [MailClient.send]. [messageId] is the RFC822 Message-ID assigned to
 * the sent message (null if the engine didn't surface one); [appendedToSent] is
 * true when a copy was filed in the account's Sent folder — best-effort, so a
 * send is reported successful even when the Sent append fails.
 */
data class SendResult(
    val messageId: String?,
    val appendedToSent: Boolean,
)

/**
 * Criteria for [MailClient.search], ANDed together (all non-null fields must match).
 * [from]/[to]/[subject]/[bodyText] are server-side substring matches; [unreadOnly]
 * restricts to messages without `\Seen`; [sinceEpochSec]/[beforeEpochSec] bound the
 * date (inclusive, day granularity on most servers). At least one field should be set.
 */
data class MailSearchCriteria(
    val from: String? = null,
    val to: String? = null,
    val subject: String? = null,
    val bodyText: String? = null,
    val unreadOnly: Boolean = false,
    val sinceEpochSec: Long? = null,
    val beforeEpochSec: Long? = null,
) {
    /** True when no criterion is set — a search with this would match the whole folder. */
    val isEmpty: Boolean
        get() = from == null && to == null && subject == null && bodyText == null &&
            !unreadOnly && sinceEpochSec == null && beforeEpochSec == null
}

/**
 * Result of a successful Proton SRP login + keyring unlock.
 *
 * SECURITY (R3): [saltedKeyPass] is the derived passphrase that unlocks the
 * account's PGP keyrings — as sensitive as the mailbox password. In v1 it is
 * held only for the lifetime of the in-memory session and is NOT persisted; on
 * process death the user re-authenticates. If silent resume is ever added it
 * must be encrypted via core/security CredentialEncryption, never stored raw.
 */
data class MailLoginResult(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val saltedKeyPass: String,
)

/**
 * Typed failures from the mail engine, mapped from the bridge's HTTP-ish status
 * codes so the connect flow can drive a staged dialog (2FA / mailbox password)
 * and distinguish a dead session from a hard auth failure.
 */
sealed class MailException(message: String) : Exception(message) {
    /** Account has TOTP enabled; retry login with a code. (bridge 412 "2fa_required") */
    class TwoFaRequired : MailException("Two-factor authentication code required")

    /** Two-password-mode account; retry login with the mailbox password. (bridge 412 "mailbox_password_required") */
    class MailboxPasswordRequired : MailException("Mailbox password required")

    /** The Go session no longer exists (process restarted / logged out) — re-login. (bridge 440) */
    class SessionExpired(message: String) : MailException(message)

    /** Wrong credentials / 2FA. (bridge 401) */
    class AuthFailed(message: String) : MailException(message)

    /** Any other bridge or protocol error, with the originating status. */
    class ProtocolError(val status: Int, message: String) : MailException(message)
}

/** Which engine backs a mail session — selects the [MailClient] in the registry. */
enum class MailEngine { PROTON, IMAP }

/**
 * Engine-specific connect parameters. The sealed type lets the single
 * [MailClient.login] carry both Proton (SRP + a SOCKS5 listener) and IMAP
 * (server coordinates + a JVM [javax.net.SocketFactory]) without an FFI-shaped
 * `socks: String?` signature leaking into the JVM engine, or vice-versa. Each
 * [MailClient] handles exactly one variant and rejects the other.
 */
sealed interface MailConnectParams {
    val username: String
    val password: String

    /** Proton SRP login (+ optional 2FA / mailbox password), routed via SOCKS5. */
    data class Proton(
        override val username: String,
        override val password: String,
        val mailboxPassword: String? = null,
        val twoFA: String? = null,
        /** Bare `host:port` of a SOCKS5 listener (the per-profile tunnel), or null. NOT a URL. */
        val socks: String? = null,
    ) : MailConnectParams

    /** Generic IMAP/SMTP with password / app-password, routed via a JVM SocketFactory. */
    data class Imap(
        override val username: String,
        override val password: String,
        val server: String,
        val port: Int,
        val smtpPort: Int,
        val tls: Boolean,
        /**
         * SMTP submission host. Null = reuse [server] (self-hosted where IMAP and
         * SMTP share a host). Real providers split them — Gmail's SMTP is
         * smtp.gmail.com, not imap.gmail.com — so [send] must dial this.
         */
        val smtpServer: String? = null,
        /** SocketFactory wrapping the per-profile tunnel, or null for a direct connection. */
        val socketFactory: javax.net.SocketFactory? = null,
    ) : MailConnectParams
}
