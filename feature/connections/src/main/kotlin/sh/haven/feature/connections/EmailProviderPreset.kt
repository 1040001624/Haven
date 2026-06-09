package sh.haven.feature.connections

/**
 * UI-only IMAP/SMTP provider presets for the EMAIL edit dialog. Selecting one
 * prefills the existing `emailServer` / `emailSmtpServer` / `emailPort` /
 * `emailSmtpPort` / `emailTls` fields — nothing here is persisted, and
 * `emailProvider` stays `"imap"` for every preset (the values `"gmail"` /
 * `"outlook"` are reserved for the deferred XOAUTH2/OAuth path).
 *
 * Each provider is password / app-password based (the lowest-friction path that
 * needs no OAuth). Outlook/Office 365 is deliberately absent: Microsoft retired
 * basic-auth/app-passwords for SMTP, so it requires OAuth and belongs in the
 * XOAUTH2 work, not here.
 *
 * SMTP security is chosen by the SMTP **port** at connect time (465 ⇒ implicit
 * TLS, otherwise STARTTLS) — see `ImapMailClient.buildSmtpProps` — so a single
 * [tls] flag (which governs IMAP) can serve providers like iCloud whose IMAP is
 * 993-implicit while SMTP is 587-STARTTLS.
 */
enum class EmailProviderPreset(
    val displayName: String,
    /** IMAP host; null for [GENERIC] (the user types it). */
    val imapServer: String?,
    val imapPort: Int,
    /** SMTP submission host; null for [GENERIC]. */
    val smtpServer: String?,
    val smtpPort: Int,
    /** Implicit TLS for IMAP (993). */
    val tls: Boolean,
    /** Where the user creates an app-password; null for [GENERIC]. */
    val appPasswordUrl: String?,
) {
    GENERIC("Custom (manual)", null, 993, null, 465, true, null),
    GMAIL(
        "Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465, true,
        "https://myaccount.google.com/apppasswords",
    ),
    ICLOUD(
        // SMTP 587 STARTTLS (the port-based decision handles it); IMAP 993 implicit.
        "iCloud", "imap.mail.me.com", 993, "smtp.mail.me.com", 587, true,
        "https://account.apple.com/account/manage",
    ),
    YAHOO(
        "Yahoo", "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, true,
        "https://login.yahoo.com/account/security/app-passwords",
    ),
    FASTMAIL(
        "Fastmail", "imap.fastmail.com", 993, "smtp.fastmail.com", 465, true,
        "https://app.fastmail.com/settings/security/devicekeys",
    );

    companion object {
        /**
         * Reverse-derive the preset from a stored IMAP host, so re-opening a
         * saved profile re-selects the named provider (and a hand-typed
         * `imap.gmail.com` is recognised too). Unknown hosts → [GENERIC].
         */
        fun fromServer(server: String?): EmailProviderPreset {
            val s = server?.trim()?.lowercase().orEmpty()
            if (s.isEmpty()) return GENERIC
            return entries.firstOrNull { it.imapServer?.lowercase() == s } ?: GENERIC
        }
    }
}
