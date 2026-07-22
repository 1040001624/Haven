package sh.haven.core.ssh

import com.jcraft.jsch.ChannelSftp
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.ssh.sftp.JschSftpSession
import sh.haven.core.ssh.sshlib.SshlibSftpSession
import java.nio.file.Files

/**
 * #58 engine routing (gate G2): a `HavenSshEngine sshlib` profile gets a
 * sshlib SFTP session when direct-TCP-capable, falls back to JSch WITH a
 * connection-log line when not, and an untoggled profile stays on JSch
 * with no engine chatter. The fallback-line assertions are what keep the
 * fallback from ever going silent.
 */
class SshEngineRoutingTest {

    private val logRepo = mockk<ConnectionLogRepository>(relaxed = true)
    private val hostKeyVerifier = mockk<HostKeyVerifier> {
        coEvery { verify(any()) } returns HostKeyResult.Trusted
    }
    private val manager = SshSessionManager(hostKeyVerifier, logRepo)
    private var server: SshServer? = null

    private val sshlibOptions = ConnectionConfig.parseSshOptions("HavenSshEngine sshlib")

    private fun jschClient(viaProxy: Boolean = false): SshClient = mockk(relaxed = true) {
        every { connectedViaProxy } returns viaProxy
        every { openSftpChannel() } returns mockk<ChannelSftp>(relaxed = true)
    }

    private fun connectedSession(
        client: SshClient,
        options: Map<String, String> = emptyMap(),
        host: String = "example.invalid",
        port: Int = 22,
    ): String {
        val sessionId = manager.registerSession("profile1", "S", client)
        manager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
        manager.storeConnectionConfig(
            sessionId,
            ConnectionConfig(
                host = host, port = port, username = "tester",
                authMethod = ConnectionConfig.AuthMethod.Password("secret"),
                sshOptions = options,
            ),
            SessionManager.NONE,
        )
        return sessionId
    }

    @After
    fun stopServer() {
        server?.stop(true)
    }

    @Test
    fun `no directive routes to JSch with no engine log lines`() {
        connectedSession(jschClient())
        val session = manager.openSftpSession("profile1")
        assertTrue("expected JSch session, got $session", session is JschSftpSession)
        coVerify(exactly = 0) {
            logRepo.logEvent(any(), any(), any(), details = match { "sshlib" in it.orEmpty() }, verboseLog = any())
        }
    }

    @Test
    fun `directive on a jump session falls back to JSch and logs why`() {
        val sessionId = connectedSession(jschClient(), sshlibOptions)
        manager.setJumpSessionId(sessionId, "the-jump-session")

        val session = manager.openSftpSession("profile1")
        assertTrue("expected JSch fallback, got $session", session is JschSftpSession)
        coVerify(timeout = 2_000) {
            logRepo.logEvent(
                "profile1", any(), any(),
                details = match { "falling back to JSch" in it.orEmpty() && "jump" in it.orEmpty() },
                verboseLog = any(),
            )
        }
    }

    @Test
    fun `directive on a proxied session falls back to JSch and logs why`() {
        connectedSession(jschClient(viaProxy = true), sshlibOptions)

        val session = manager.openSftpSession("profile1")
        assertTrue("expected JSch fallback, got $session", session is JschSftpSession)
        coVerify(timeout = 2_000) {
            logRepo.logEvent(
                "profile1", any(), any(),
                details = match { "falling back to JSch" in it.orEmpty() && "prox" in it.orEmpty() },
                verboseLog = any(),
            )
        }
    }

    @Test
    fun `directive on a direct session dials sshlib and logs the engine line`() {
        val root = Files.createTempDirectory("routing-sftp")
        val sshd = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hk", ".ser"))
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }
        sshd.start()
        server = sshd

        connectedSession(jschClient(), sshlibOptions, host = "127.0.0.1", port = sshd.port)

        val session = manager.openSftpSession("profile1")
        assertNotNull(session)
        assertTrue("expected sshlib session, got $session", session is SshlibSftpSession)
        coVerify(timeout = 2_000) {
            logRepo.logEvent(
                "profile1", any(), any(),
                details = match { "sshlib engine" in it.orEmpty() },
                verboseLog = any(),
            )
        }
        session!!.close()
    }
}
