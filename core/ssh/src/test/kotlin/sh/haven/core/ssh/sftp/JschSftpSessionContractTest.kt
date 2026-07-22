package sh.haven.core.ssh.sftp

import kotlinx.coroutines.runBlocking
import org.junit.After
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.SshClient

/** [SftpSessionContractTest] on the JSch engine — the pre-#58 behaviour pin. */
class JschSftpSessionContractTest : SftpSessionContractTest() {

    private var client: SshClient? = null

    override fun openSession(host: String, port: Int, username: String, password: String): SftpSession {
        val c = SshClient()
        client = c
        runBlocking {
            c.connect(
                ConnectionConfig(
                    host = host,
                    port = port,
                    username = username,
                    authMethod = ConnectionConfig.AuthMethod.Password(password),
                ),
            )
        }
        return c.openSftpSession()
    }

    @After
    fun closeClient() {
        try { client?.close() } catch (_: Exception) { /* best effort */ }
    }
}
