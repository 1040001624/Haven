package sh.haven.core.ssh.sshlib

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.ConnectionConfig.AuthMethod

/** The phase-1 capability gate (#58): what may dial sshlib, what falls back. */
class SshlibSftpConnectorTest {

    private fun config(auth: AuthMethod) =
        ConnectionConfig(host = "h", username = "u", authMethod = auth)

    private fun reason(
        auth: AuthMethod = AuthMethod.Password("p"),
        hasJump: Boolean = false,
        hasProxy: Boolean = false,
    ) = SshlibSftpConnector.unsupportedReason(config(auth), hasJump, hasProxy)

    @Test
    fun `direct password connection is supported`() = assertNull(reason())

    @Test
    fun `plain private key is supported`() =
        assertNull(reason(AuthMethod.PrivateKey(byteArrayOf(1), "pw")))

    @Test
    fun `key pool without certificates is supported`() = assertNull(
        reason(
            AuthMethod.PrivateKeys(
                listOf(AuthMethod.PrivateKeys.KeyEntry("k", byteArrayOf(1))),
            ),
        ),
    )

    @Test
    fun `jump hosts fall back`() = assertNotNull(reason(hasJump = true))

    @Test
    fun `proxied connections fall back`() = assertNotNull(reason(hasProxy = true))

    @Test
    fun `FIDO keys fall back`() =
        assertNotNull(reason(AuthMethod.FidoKey(skKeyData = byteArrayOf(1))))

    @Test
    fun `certificate-carrying keys fall back`() {
        assertNotNull(
            reason(AuthMethod.PrivateKey(byteArrayOf(1), certificateBytes = byteArrayOf(2))),
        )
        assertNotNull(
            reason(
                AuthMethod.PrivateKeys(
                    listOf(
                        AuthMethod.PrivateKeys.KeyEntry("plain", byteArrayOf(1)),
                        AuthMethod.PrivateKeys.KeyEntry("cert", byteArrayOf(1), certificateBytes = byteArrayOf(2)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `multi-method chains fall back`() = assertNotNull(
        reason(AuthMethod.Multi(listOf(AuthMethod.Password("p")))),
    )
}
