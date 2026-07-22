package sh.haven.core.ssh

import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * Engine-agnostic contract for one-shot remote exec (#58 phase 3): stdout,
 * stderr, exit codes, large output, and the timeout shape of [ExecResult],
 * against a real MINA sshd with an in-process scripted CommandFactory.
 *
 * Engines: [JschExecContractTest] runs today. The sshlib subclass lands when
 * a sshlib release carries exit-status support (connectbot/cbssh#232) — the
 * `GAP exec exit status` probe in SshlibCapabilitySpikeTest flips at that
 * moment.
 */
abstract class ExecContractTest {

    protected lateinit var server: SshServer
    protected var serverPort: Int = 0

    /** Run [command] on the engine under test against 127.0.0.1:[serverPort]. */
    protected abstract fun exec(command: String, timeoutMs: Long? = null): ExecResult

    @Before
    fun startServer() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("exec-hostkey", ".ser"))
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            commandFactory = CommandFactory { _, command -> ScriptedCommand(command) }
        }
        server.start()
        serverPort = server.port
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
    }

    @Test
    fun `stdout and zero exit status`() {
        val result = exec("out")
        assertEquals("hello\n", result.stdout)
        assertEquals("", result.stderr)
        assertEquals(0, result.exitStatus)
        assertFalse(result.timedOut)
    }

    @Test
    fun `stderr and non-zero exit status`() {
        val result = exec("fail")
        assertEquals("", result.stdout)
        assertEquals("oops\n", result.stderr)
        assertEquals(3, result.exitStatus)
        assertFalse(result.timedOut)
    }

    @Test
    fun `both streams captured independently`() {
        val result = exec("both")
        assertEquals("to-stdout\n", result.stdout)
        assertEquals("to-stderr\n", result.stderr)
        assertEquals(0, result.exitStatus)
    }

    @Test
    fun `large stdout arrives intact`() {
        val result = exec("big")
        assertEquals(BIG_PAYLOAD_BYTES, result.stdout.length)
        // Spot-check the repeating pattern survived windowing/reassembly.
        assertTrue(result.stdout.startsWith(BIG_LINE))
        assertTrue(result.stdout.endsWith(BIG_LINE))
        assertEquals(0, result.exitStatus)
    }

    @Test
    fun `timeout aborts a hung command with the documented shape`() {
        val startedAt = System.nanoTime()
        val result = exec("hang", timeoutMs = 1_500)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("timedOut flag not set", result.timedOut)
        assertEquals(-1, result.exitStatus)
        assertTrue("timeout took ${elapsedMs}ms", elapsedMs < 10_000)
    }

    protected companion object {
        const val BIG_LINE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde\n" // 64 bytes
        const val BIG_PAYLOAD_BYTES = 64 * 16_384 // 1 MiB

        /** In-process command runner: deterministic, OS-independent. */
        private class ScriptedCommand(private val command: String) : Command {
            private var out: OutputStream? = null
            private var err: OutputStream? = null
            private var exit: ExitCallback? = null

            @Volatile
            private var worker: Thread? = null

            override fun setInputStream(value: InputStream?) {}
            override fun setOutputStream(value: OutputStream?) { out = value }
            override fun setErrorStream(value: OutputStream?) { err = value }
            override fun setExitCallback(value: ExitCallback?) { exit = value }

            override fun start(channel: ChannelSession?, env: Environment?) {
                worker = Thread({
                    try {
                        when (command) {
                            "out" -> { out!!.write("hello\n".toByteArray()); out!!.flush(); exit!!.onExit(0) }
                            "fail" -> { err!!.write("oops\n".toByteArray()); err!!.flush(); exit!!.onExit(3) }
                            "both" -> {
                                out!!.write("to-stdout\n".toByteArray()); out!!.flush()
                                err!!.write("to-stderr\n".toByteArray()); err!!.flush()
                                exit!!.onExit(0)
                            }
                            "big" -> {
                                val line = BIG_LINE.toByteArray()
                                repeat(BIG_PAYLOAD_BYTES / line.size) { out!!.write(line) }
                                out!!.flush()
                                exit!!.onExit(0)
                            }
                            "hang" -> {
                                // Emits nothing and never exits; unstuck only by
                                // the client's timeout tearing the channel down.
                                while (!Thread.currentThread().isInterrupted) Thread.sleep(100)
                            }
                            else -> { err!!.write("unknown command\n".toByteArray()); exit!!.onExit(127) }
                        }
                    } catch (_: InterruptedException) {
                        // destroy() path
                    } catch (_: java.io.IOException) {
                        // channel torn down under us (timeout path) — fine
                    }
                }, "scripted-command").apply { isDaemon = true; start() }
            }

            override fun destroy(channel: ChannelSession?) {
                worker?.interrupt()
            }
        }
    }
}

/** [ExecContractTest] on the JSch engine — the behaviour the sshlib engine must match. */
class JschExecContractTest : ExecContractTest() {

    private var client: SshClient? = null

    override fun exec(command: String, timeoutMs: Long?): ExecResult {
        val c = client ?: SshClient().also { fresh ->
            client = fresh
            runBlocking {
                fresh.connect(
                    ConnectionConfig(
                        host = "127.0.0.1",
                        port = serverPort,
                        username = "tester",
                        authMethod = ConnectionConfig.AuthMethod.Password("secret"),
                    ),
                )
            }
        }
        return runBlocking { c.execCommand(command, timeoutMs) }
    }

    @After
    fun closeClient() {
        try { client?.close() } catch (_: Exception) { /* best effort */ }
        client = null
    }
}
