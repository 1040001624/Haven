package sh.haven.feature.mail

import sh.haven.core.mail.MailSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the [MailBackend] for a profile, mirroring
 * `feature/sftp`'s TransportSelector. v1 has only the Proton engine; future JVM
 * IMAP/Gmail/Outlook engines add branches here keyed on the connected session.
 */
@Singleton
class MailTransportSelector @Inject constructor(
    private val mailSessionManager: MailSessionManager,
) {
    /** A backend for [profileId], or null if the profile has no connected session. */
    fun resolve(profileId: String): MailBackend? {
        val sessionId = mailSessionManager.getSessionIdForProfile(profileId) ?: return null
        return ProtonMailBackend(mailSessionManager.mailClient, sessionId)
    }
}
