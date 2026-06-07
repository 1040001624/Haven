package sh.haven.core.mail

/**
 * Provider-agnostic mail engine. v1 has a single Proton implementation
 * ([ProtonMailClient]) backed by the Go mailbridge; a JVM Jakarta Mail
 * implementation for IMAP/Gmail/Outlook is planned for stage 2.
 *
 * Session state (logged-in, keyring-unlocked accounts) lives behind the
 * implementation, keyed by an opaque [sessionId] the caller mints. All methods
 * are main-safe (they switch to a background dispatcher internally) and throw
 * [MailException] subtypes on failure.
 */
interface MailClient {

    /**
     * Authenticate and unlock the account. Throws [MailException.TwoFaRequired]
     * or [MailException.MailboxPasswordRequired] when the caller must re-prompt
     * and retry with [twoFA] / [mailboxPassword] filled in (the same
     * [sessionId] can be reused — no session is registered until unlock
     * succeeds).
     *
     * @param socks bare `host:port` of a SOCKS5 listener to route through (the
     *   per-profile tunnel), or null for a direct connection. NOT a URL.
     */
    suspend fun login(
        sessionId: String,
        username: String,
        password: String,
        mailboxPassword: String? = null,
        twoFA: String? = null,
        socks: String? = null,
    ): MailLoginResult

    /** List the account's folders/labels. */
    suspend fun listFolders(sessionId: String): List<MailFolder>

    /** List message envelopes in [folderId], newest first when [desc]. */
    suspend fun listMessages(sessionId: String, folderId: String, desc: Boolean = true): List<MailMessage>

    /** Fetch and decrypt one message, returning the raw RFC822 MIME bytes. */
    suspend fun getMessageRaw(sessionId: String, messageId: String): ByteArray

    /** Revoke and drop the session. */
    suspend fun logout(sessionId: String)
}
