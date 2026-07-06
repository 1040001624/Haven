package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Trust-on-first-use record for a remote-desktop server's TLS leaf
 * certificate, keyed by host:port. The SSH equivalent is [KnownHost];
 * this covers the VNC (VeNCrypt X509) and RDP TLS paths, which used to
 * accept any certificate (security-review criticals #1 and #2).
 *
 * [sha256] is the lowercase hex SHA-256 of the DER-encoded leaf cert.
 */
@Entity(tableName = "known_tls_certs")
data class KnownTlsCert(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val hostname: String,
    val port: Int,
    val sha256: String,
    val firstSeen: Long = System.currentTimeMillis(),
)
