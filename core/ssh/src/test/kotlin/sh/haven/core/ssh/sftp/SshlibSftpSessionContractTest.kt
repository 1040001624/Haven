package sh.haven.core.ssh.sftp

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.SshIoException
import sh.haven.core.ssh.sshlib.SshlibSftpConnector

/**
 * [SftpSessionContractTest] on the sshlib engine (#58 phase 1) — same suite,
 * same MINA server, different wire implementation. Plus the sshlib-specific
 * host-key fail-closed gate.
 */
class SshlibSftpSessionContractTest : SftpSessionContractTest() {

    override fun openSession(host: String, port: Int, username: String, password: String): SftpSession =
        runBlocking {
            SshlibSftpConnector.connect(
                ConnectionConfig(
                    host = host,
                    port = port,
                    username = username,
                    authMethod = ConnectionConfig.AuthMethod.Password(password),
                ),
                trustingVerifier(),
            )
        }

    /** Haven verifier that trusts the test server's (ephemeral) host key. */
    private fun trustingVerifier(): HostKeyVerifier = mockk {
        coEvery { verify(any()) } returns HostKeyResult.Trusted
    }

    // --- sshlib-specific: host keys are fail-closed ---

    @Test
    fun `untrusted host key aborts the connection`() {
        val distrustingVerifier = mockk<HostKeyVerifier> {
            coEvery { verify(any()) } answers {
                HostKeyResult.NewHost(firstArg<KnownHostEntry>())
            }
        }
        val ex = assertThrows(SshIoException::class.java) {
            runBlocking {
                SshlibSftpConnector.connect(
                    ConnectionConfig(
                        host = "127.0.0.1",
                        port = serverPort,
                        username = "tester",
                        authMethod = ConnectionConfig.AuthMethod.Password("secret"),
                    ),
                    distrustingVerifier,
                )
            }
        }
        assertNotNull(ex.message)
    }
}
