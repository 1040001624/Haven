package sh.haven.core.data.agent

import sh.haven.core.data.db.KnownTlsCertDao
import sh.haven.core.data.db.entities.KnownTlsCert
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed class TlsCertResult {
    /** Fingerprint matches the stored one for this host:port. */
    data object Trusted : TlsCertResult()

    /** No cert stored yet for this host:port (first connect). */
    data class NewCert(val sha256: String) : TlsCertResult()

    /** A cert is stored but the presented one differs — possible MITM. */
    data class CertChanged(val stored: String, val presented: String) : TlsCertResult()
}

/**
 * Trust-on-first-use verifier for remote-desktop TLS server certificates,
 * the TLS analogue of [sh.haven.core.ssh.HostKeyVerifier]. Used by the VNC
 * (VeNCrypt X509) and RDP paths, which previously accepted any certificate
 * (security-review criticals #1 and #2).
 *
 * Policy: first cert seen for a host:port is remembered; a later mismatch
 * fails closed. Callers store the fingerprint via [accept] only after the
 * rest of the connection succeeds.
 */
@Singleton
class TlsCertVerifier @Inject constructor(
    private val dao: KnownTlsCertDao,
) {

    suspend fun verify(hostname: String, port: Int, certDer: ByteArray): TlsCertResult {
        val presented = fingerprint(certDer)
        val stored = dao.findByHostPort(hostname, port)
            ?: return TlsCertResult.NewCert(presented)
        return if (stored.sha256 == presented) {
            TlsCertResult.Trusted
        } else {
            TlsCertResult.CertChanged(stored = stored.sha256, presented = presented)
        }
    }

    suspend fun accept(hostname: String, port: Int, sha256: String) {
        dao.deleteByHostPort(hostname, port)
        dao.upsert(KnownTlsCert(hostname = hostname, port = port, sha256 = sha256))
    }

    companion object {
        /** Lowercase hex SHA-256 of a DER-encoded certificate. */
        fun fingerprint(certDer: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(certDer)
                .joinToString("") { "%02x".format(it) }
    }
}
