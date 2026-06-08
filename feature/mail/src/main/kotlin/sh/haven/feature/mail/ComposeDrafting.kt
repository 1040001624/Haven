package sh.haven.feature.mail

/**
 * Editable state for the compose pane. Raw [String] recipient fields (parsed to
 * address lists only at send time by [ComposeDrafting.parseRecipients]) so the
 * Compose UI binds directly to them.
 */
data class ComposeDraft(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val showCcBcc: Boolean = false,
    val sending: Boolean = false,
    /** Non-null when the last send attempt failed; shown in-form so the user can retry. */
    val sendError: String? = null,
    /** The connected account this draft sends from (the compose "From"); set by the ViewModel. */
    val fromProfileId: String = "",
) {
    /** Any user-entered content — drives the discard-confirm on back/close. */
    val isDirty: Boolean
        get() = to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank() ||
            subject.isNotBlank() || body.isNotBlank()
}

/**
 * Pure, Android-free helpers for building reply/forward drafts and parsing
 * recipient input. Kept free of `android.*` (notably `android.util.Patterns`) so
 * it unit-tests on the plain JVM, mirroring the [MimeParser] object.
 *
 * The UI/ViewModel owns all locale-sensitive text (date formatting, the
 * attribution and forwarded-header prose) and passes it in — this object only
 * assembles structure.
 */
object ComposeDrafting {

    /** Split a recipient field on commas/semicolons/whitespace; trim, drop blanks, dedupe. */
    fun parseRecipients(raw: String): List<String> =
        raw.split(Regex("[,;\\s]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    /** Add a single "Re: " prefix, idempotently (an existing "Re:" of any case is kept). */
    fun ensureRePrefix(subject: String): String =
        if (subject.trimStart().startsWith("re:", ignoreCase = true)) subject else "Re: $subject"

    /** Add a single "Fwd: " prefix, idempotently (existing "Fwd:"/"Fw:" of any case is kept). */
    fun ensureFwdPrefix(subject: String): String {
        val t = subject.trimStart()
        return if (t.startsWith("fwd:", ignoreCase = true) || t.startsWith("fw:", ignoreCase = true)) {
            subject
        } else {
            "Fwd: $subject"
        }
    }

    /**
     * Reduce a display form ("Alice <a@x>" / "<a@x>") to the bare address; returns
     * the trimmed input unchanged when there are no angle brackets.
     */
    fun extractAddress(displayOrAddr: String): String {
        val s = displayOrAddr.trim()
        val open = s.lastIndexOf('<')
        if (open >= 0) {
            val close = s.indexOf('>', open + 1)
            if (close > open) return s.substring(open + 1, close).trim()
        }
        return s
    }

    /**
     * A quoted reply body: a leading blank line (room for the user's text), the
     * attribution line, then every original line prefixed with "> " (empty lines
     * become ">"). CRLFs are normalised to LF.
     */
    fun quoteBody(bodyText: String, attributionLine: String): String {
        val quoted = bodyText.replace("\r\n", "\n").split("\n")
            .joinToString("\n") { line -> if (line.isEmpty()) ">" else "> $line" }
        return "\n$attributionLine\n$quoted"
    }

    /**
     * Build a reply draft. [attributionLine] (e.g. "On 5 Jun 2026, Alice wrote:")
     * is pre-formatted by the caller. When [replyAll], the original recipients (minus
     * the new To address and [selfAddress], if known) become Cc. We don't reliably
     * know the account's own address app-wide, so [selfAddress] may be null and the
     * sender could end up Cc'ing themselves — an honest limitation.
     */
    fun buildReply(
        parsed: ParsedMessage,
        replyAll: Boolean,
        attributionLine: String,
        selfAddress: String?,
    ): ComposeDraft {
        val toAddr = extractAddress(parsed.from)
        val ccList = if (replyAll) {
            parsed.to.map { extractAddress(it) }
                .filter { it.isNotBlank() }
                .filter { selfAddress == null || !it.equals(selfAddress, ignoreCase = true) }
                .filter { !it.equals(toAddr, ignoreCase = true) }
                .distinct()
        } else {
            emptyList()
        }
        return ComposeDraft(
            to = toAddr,
            cc = ccList.joinToString(", "),
            subject = ensureRePrefix(parsed.subject),
            body = quoteBody(parsed.bodyText, attributionLine),
            showCcBcc = ccList.isNotEmpty(),
        )
    }

    /**
     * Build a forward draft: empty To, "Fwd: " subject, and a forwarded-header
     * block ([forwardHeader] separator + From/To/Subject) followed by the original
     * body, unquoted.
     */
    fun buildForward(parsed: ParsedMessage, forwardHeader: String): ComposeDraft {
        val body = buildString {
            append('\n')
            append(forwardHeader).append('\n')
            append("From: ").append(parsed.from).append('\n')
            if (parsed.to.isNotEmpty()) append("To: ").append(parsed.to.joinToString(", ")).append('\n')
            append("Subject: ").append(parsed.subject).append('\n')
            append('\n')
            append(parsed.bodyText)
        }
        return ComposeDraft(
            subject = ensureFwdPrefix(parsed.subject),
            body = body,
        )
    }
}
