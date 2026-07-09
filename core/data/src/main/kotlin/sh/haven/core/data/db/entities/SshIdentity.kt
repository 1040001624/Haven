package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A named, reusable credential bundle — username + optional password +
 * optional SSH key — that connections and connection groups can reference
 * instead of carrying inline credentials (#360, Termius-style identities).
 *
 * [password] is encrypted at rest with `CredentialEncryption` (same envelope
 * as profile passwords); the encrypt/decrypt boundary is
 * `SshIdentityRepository`. [keyId] references an [SshKey] row; the key
 * material itself stays in the key store.
 *
 * Assignment lives on the referencing side: `ConnectionProfile.identityId`
 * and `ConnectionGroup.identityId`. Resolution order and the explicit
 * "none" opt-out are documented on [NONE_ID].
 */
@Entity(tableName = "ssh_identities")
data class SshIdentity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val username: String,
    /** Password, encrypted at rest; null = key-only identity. */
    val password: String? = null,
    /** [SshKey] reference; null = password-only identity. */
    val keyId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * Sentinel for `ConnectionProfile.identityId` meaning "explicitly use
         * this connection's own inline credentials, even if its group has an
         * identity". Distinct from null, which means "inherit the group's
         * identity when one is set".
         */
        const val NONE_ID = "__none__"
    }
}
