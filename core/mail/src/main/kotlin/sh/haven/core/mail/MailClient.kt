package sh.haven.core.mail

/**
 * Provider-agnostic mail engine. Implementations: [ProtonMailClient] (Go
 * mailbridge, SRP) and the JVM IMAP/SMTP engine (Stage 2a). Each handles one
 * [MailConnectParams] variant; [MailSessionManager] routes by the session's
 * [MailEngine].
 *
 * Session state (logged-in, keyring-unlocked accounts) lives behind the
 * implementation, keyed by an opaque [sessionId] the caller mints. All methods
 * are main-safe (they switch to a background dispatcher internally) and throw
 * [MailException] subtypes on failure.
 */
interface MailClient {

    /**
     * Authenticate and unlock the account using engine-specific [params]. Throws
     * [MailException.TwoFaRequired] / [MailException.MailboxPasswordRequired]
     * when the caller must re-prompt and retry (the same [sessionId] can be
     * reused — no session is registered until unlock succeeds). Throws
     * [IllegalArgumentException] if handed the wrong [MailConnectParams] variant.
     */
    suspend fun login(
        sessionId: String,
        params: MailConnectParams,
    ): MailLoginResult

    /** List the account's folders/labels. */
    suspend fun listFolders(sessionId: String): List<MailFolder>

    /**
     * List a page of message envelopes in [folderId], newest first when [desc].
     * [limit] caps how many are fetched (so a multi-thousand-message inbox doesn't
     * pull every envelope over the tunnel); [offset] skips that many from the
     * newest end, so `offset = page * limit` walks older pages ("Load older").
     */
    suspend fun listMessages(
        sessionId: String,
        folderId: String,
        desc: Boolean = true,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): List<MailMessage>

    /** Fetch and decrypt one message, returning the raw RFC822 MIME bytes. */
    suspend fun getMessageRaw(sessionId: String, messageId: String): ByteArray

    /**
     * Send [mail] from the connected account, returning the assigned Message-ID
     * and whether a copy was filed in Sent. Throws [MailException] on
     * transport/auth failure. The Proton engine is not yet wired for send and
     * throws [MailException.ProtocolError] with status 501.
     */
    suspend fun send(sessionId: String, mail: OutgoingMail): SendResult

    /**
     * Mark [messageId] read/unread (the IMAP `\Seen` flag). Used by Mail Rules'
     * filter actions. The Proton engine throws [MailException.ProtocolError] (501).
     */
    suspend fun setSeen(sessionId: String, messageId: String, seen: Boolean)

    /** Set/clear the IMAP `\Flagged` (starred) flag on [messageId]. Proton: 501. */
    suspend fun setFlagged(sessionId: String, messageId: String, flagged: Boolean)

    /**
     * Move [messageId] to [destFolderId] (server-side copy + delete + expunge on
     * the source). Proton: 501. On Gmail this relabels — "All Mail" retains the copy.
     */
    suspend fun moveMessage(sessionId: String, messageId: String, destFolderId: String)

    /**
     * Delete [messageId] (set `\Deleted` + expunge). On Gmail this moves it to Trash
     * rather than erasing it. Proton: 501.
     */
    suspend fun deleteMessage(sessionId: String, messageId: String)

    /**
     * Copy [messageId] into [destFolderId], leaving the original in place (server-side
     * IMAP COPY). On Gmail this *applies a label* — the message keeps its current
     * folders/labels and gains [destFolderId]. Use for additive labelling (vs
     * [moveMessage], which removes the source). Proton: 501.
     */
    suspend fun copyMessage(sessionId: String, messageId: String, destFolderId: String)

    /**
     * Read [folderId]'s IMAP UID state for the Mail-Rules poller (UIDVALIDITY, UIDNEXT,
     * highest existing UID). Proton: throws [MailException.ProtocolError] (501).
     */
    suspend fun folderUidState(sessionId: String, folderId: String): MailFolderUidState

    /**
     * Fetch envelopes for messages whose UID is strictly greater than [sinceUid] in
     * [folderId], oldest-first, capped at [max]. Each carries its UID so the poller can
     * advance its high-water-mark. Proton: 501.
     */
    suspend fun listSince(
        sessionId: String,
        folderId: String,
        sinceUid: Long,
        max: Int,
    ): List<MailNewMessage>

    /**
     * Server-side search of [folderId] for envelopes matching [criteria] (newest-first,
     * capped at [limit]). Returns the same envelope shape as [listMessages] so the ids
     * feed straight into [getMessageRaw] / the modify ops. Proton: 501.
     */
    suspend fun search(
        sessionId: String,
        folderId: String,
        criteria: MailSearchCriteria,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): List<MailMessage>

    /**
     * Append [mail] to the account's Drafts folder (flagged `\Draft`), without sending.
     * Returns the Drafts folder id it landed in. Proton: 501.
     */
    suspend fun saveDraft(sessionId: String, mail: OutgoingMail): String

    /**
     * Create a new folder/label [name] (IMAP CREATE). On Gmail this is a new label.
     * Returns the created folder's id (fullName). Throws if it already exists. Proton: 501.
     */
    suspend fun createFolder(sessionId: String, name: String): String

    /**
     * Delete the folder/label [folderId] (IMAP DELETE). On Gmail this removes the *label*
     * (messages survive in All Mail); on a plain IMAP server it deletes the mailbox AND its
     * messages — so this is destructive. Refuses the special-use system folders. Proton: 501.
     */
    suspend fun deleteFolder(sessionId: String, folderId: String)

    /** Revoke and drop the session. */
    suspend fun logout(sessionId: String)

    companion object {
        /** Default message-list page size — most-recent N envelopes per fetch. */
        const val DEFAULT_PAGE_SIZE = 100
    }
}
