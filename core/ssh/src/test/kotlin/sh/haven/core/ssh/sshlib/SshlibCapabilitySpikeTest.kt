package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.compression.BuiltinCompressions
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SshClient as SshlibClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.transport.Transport
import org.connectbot.sshlib.transport.TransportFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.SshClient
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Phase-2 capability spike for the #58 dual-engine migration: each probe pins
 * sshlib 0.3.1's ACTUAL behaviour against a real MINA sshd, so later phases
 * are sorted into pure-Haven work vs blocked-on-upstream by a test verdict,
 * not an assumption. Probes that document a GAP assert the failure — when a
 * new sshlib release closes the gap, the probe fails and tells us to unlock
 * the corresponding phase. Results are recorded on issue #58.
 *
 * JSch-parity probes live here too (PQ KEX) so both engines run against the
 * same server restriction in one place.
 */
class SshlibCapabilitySpikeTest {

    /** A hung probe is a verdict too — surface it as TestTimedOutException instead of wedging CI. */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(90)

    private val servers = mutableListOf<SshServer>()
    private val roots = mutableListOf<Path>()
    private val clients = mutableListOf<SshlibClient>()

    private fun newServer(configure: SshServer.() -> Unit = {}): SshServer {
        val root = Files.createTempDirectory("spike-sftp").also { roots.add(it) }
        val server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(
                Files.createTempFile("spike-hostkey", ".ser"),
            )
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root)
            configure()
        }
        server.start()
        servers.add(server)
        return server
    }

    @After
    fun tearDown() {
        clients.forEach { runBlocking { runCatching { it.disconnect() } } }
        servers.forEach { runCatching { it.stop(true) } }
        roots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private val trustAll = object : org.connectbot.sshlib.HostKeyVerifier {
        override suspend fun verify(key: PublicKey): Boolean = true
    }

    private fun sshlibClient(configure: SshClientConfig.Builder.() -> Unit): SshlibClient =
        SshlibClient(SshClientConfig { hostKeyVerifier = trustAll; configure() })
            .also { clients.add(it) }

    private fun connectAndAuth(client: SshlibClient): ConnectResult = runBlocking {
        val result = client.connect()
        if (result is ConnectResult.Success) {
            assertEquals(AuthResult.Success, client.authenticatePassword("tester", "secret"))
        }
        result
    }

    private fun restrictServerKex(server: SshServer, factory: BuiltinDHFactories) {
        server.keyExchangeFactories = listOf(ServerBuilder.DH2KEX.apply(factory))
    }

    // ------------------------------------------------------------------
    // Algorithm-list overrides (SshOptionsApplier parity — phase 9)
    // ------------------------------------------------------------------

    @Test
    fun `PASS kexAlgorithms override drives negotiation`() {
        val server = newServer()
        val client = sshlibClient {
            host = "127.0.0.1"; port = server.port
            kexAlgorithms = "diffie-hellman-group14-sha256"
        }
        assertTrue(connectAndAuth(client) is ConnectResult.Success)
        assertEquals("diffie-hellman-group14-sha256", client.connectionInfo?.kexAlgorithm)
    }

    @Test
    fun `PASS kex mismatch is surfaced as AlgorithmMismatch`() {
        val server = newServer()
        restrictServerKex(server, BuiltinDHFactories.dhg14_256)
        val client = sshlibClient {
            host = "127.0.0.1"; port = server.port
            kexAlgorithms = "curve25519-sha256"
        }
        val result = connectAndAuth(client)
        assertTrue("expected AlgorithmMismatch, got $result", result is ConnectResult.AlgorithmMismatch)
    }

    @Test
    fun `PASS cipher and MAC overrides drive negotiation`() {
        val server = newServer()
        val client = sshlibClient {
            host = "127.0.0.1"; port = server.port
            encryptionAlgorithms = "aes128-ctr"
            macAlgorithms = "hmac-sha2-256"
        }
        assertTrue(connectAndAuth(client) is ConnectResult.Success)
        val info = client.connectionInfo!!
        assertEquals("aes128-ctr", info.encryptionAlgorithmC2S)
        assertEquals("aes128-ctr", info.encryptionAlgorithmS2C)
        assertEquals("hmac-sha2-256", info.macAlgorithmC2S)
    }

    // ------------------------------------------------------------------
    // Post-quantum KEX parity (issue #296 users; gate on ever defaulting)
    // ------------------------------------------------------------------

    @Test
    fun `RIG mlkem768 and sntrup761 are servable by the MINA+BC test rig`() {
        // If this fails the PQ probes below are untrustworthy — fix the rig
        // (BouncyCastle version) before reading their verdicts.
        assertTrue("mlkem768x25519 unsupported by rig", BuiltinDHFactories.mlkem768x25519.isSupported)
        assertTrue("sntrup761x25519 unsupported by rig", BuiltinDHFactories.sntrup761x25519_openssh.isSupported)
    }

    @Test
    fun `PASS sshlib negotiates mlkem768x25519 post-quantum KEX`() {
        assumeTrue(BuiltinDHFactories.mlkem768x25519.isSupported)
        val server = newServer()
        restrictServerKex(server, BuiltinDHFactories.mlkem768x25519)
        val client = sshlibClient { host = "127.0.0.1"; port = server.port }
        assertTrue(connectAndAuth(client) is ConnectResult.Success)
        assertEquals("mlkem768x25519-sha256", client.connectionInfo?.kexAlgorithm)
    }

    @Test
    fun `GAP sshlib cannot negotiate sntrup761x25519 — flips when upstream adds it`() {
        assumeTrue(BuiltinDHFactories.sntrup761x25519_openssh.isSupported)
        val server = newServer()
        restrictServerKex(server, BuiltinDHFactories.sntrup761x25519_openssh)
        val client = sshlibClient { host = "127.0.0.1"; port = server.port }
        val result = connectAndAuth(client)
        assertFalse(
            "sshlib now negotiates sntrup761 — remove this gap and revisit PQ parity (#58 P2)",
            result is ConnectResult.Success,
        )
    }

    @Test
    fun `PARITY JSch negotiates mlkem768x25519 by default`() {
        jschParityProbe(BuiltinDHFactories.mlkem768x25519)
    }

    @Test
    fun `PARITY JSch default proposal omits sntrup761 — reachable only via KexAlgorithms opt-in`() {
        // Default proposal → negotiation fails (JSch 2.28.4 ships sntrup761
        // support but does not propose it by default)…
        try {
            jschParityProbe(BuiltinDHFactories.sntrup761x25519_openssh)
            throw IllegalStateException("JSch now proposes sntrup761 by default — update the PQ parity table (#58 P2)")
        } catch (expected: AssertionError) {
            assertTrue("unexpected failure shape: $expected", "Algorithm negotiation fail" in (expected.cause?.message ?: ""))
        }
        // …but the user-facing SshOptions path (issue #155 mechanism) reaches it.
        jschParityProbe(
            BuiltinDHFactories.sntrup761x25519_openssh,
            sshOptions = ConnectionConfig.parseSshOptions("KexAlgorithms +sntrup761x25519-sha512@openssh.com"),
        )
    }

    private fun jschParityProbe(
        factory: BuiltinDHFactories,
        sshOptions: Map<String, String> = emptyMap(),
    ) {
        assumeTrue(factory.isSupported)
        val server = newServer()
        restrictServerKex(server, factory)
        val jsch = SshClient()
        try {
            runBlocking {
                try {
                    jsch.connect(
                        ConnectionConfig(
                            host = "127.0.0.1", port = server.port, username = "tester",
                            authMethod = ConnectionConfig.AuthMethod.Password("secret"),
                            sshOptions = sshOptions,
                        ),
                    )
                } catch (e: Exception) {
                    throw AssertionError("JSch connect failed on ${factory.name}: $e", e)
                }
            }
            // Liveness via SFTP — the rig has no CommandFactory, so the
            // exec-based SshClient.isAlive() is rejected on a healthy session.
            jsch.openSftpChannel().disconnect()
        } finally {
            jsch.close()
        }
    }

    // ------------------------------------------------------------------
    // Compression + rekey
    // ------------------------------------------------------------------

    @Test
    fun `PASS compression-enabled session roundtrips SFTP data`() {
        val server = newServer {
            compressionFactories = listOf(BuiltinCompressions.zlib, BuiltinCompressions.none)
        }
        val client = sshlibClient { host = "127.0.0.1"; port = server.port; enableCompression = true }
        assertTrue(connectAndAuth(client) is ConnectResult.Success)
        // Highly compressible payload so a broken zlib layer would corrupt it.
        val payload = "haven".repeat(40_000).toByteArray()
        sftpRoundtrip(client, payload)
    }

    @Test
    fun `PASS large transfer with default rekey limits roundtrips`() {
        // Baseline for the rekey probes: same 2 MiB transfer, no rekey
        // triggered (defaults are 1 GiB / 1 h) — isolates rekey as the
        // variable in the GAP probe below.
        val server = newServer()
        val client = sshlibClient { host = "127.0.0.1"; port = server.port }
        assertTrue(connectAndAuth(client) is ConnectResult.Success)
        sftpRoundtrip(client, Random(58).nextBytes(2 * 1024 * 1024))
    }

    @Test
    fun `GAP client byte-limit rekey during in-flight SFTP writes kills the channel — flips when upstream fixes`() {
        // sshlib 0.3.1: with rekeyBytesLimit=256 KiB, the transfer dies right
        // past the limit (ChannelClosedException at ~write 294912). Real-world
        // impact: any single SFTP transfer larger than the rekey byte limit
        // (default 1 GiB) dies mid-flight. Upstream bug to file; when a fix
        // ships this probe fails and the workaround/pin can be dropped.
        val server = newServer()
        val client = sshlibClient {
            host = "127.0.0.1"; port = server.port
            rekeyBytesLimit = 256L * 1024
        }
        assertTrue(connectAndAuth(client) is ConnectResult.Success)
        val outcome = runCatching { sftpRoundtrip(client, Random(58).nextBytes(2 * 1024 * 1024)) }
        assertTrue(
            "rekey-under-load now works — remove this GAP probe and re-verify large transfers (#58 P2)",
            outcome.isFailure,
        )
    }

    @Test
    fun `GAP interval rekey wedges an idle session — flips when upstream fixes`() {
        // sshlib 0.3.1: after interval-triggered rekeys on an idle session the
        // next SFTP op suspends forever (no error — a hang). Worse than the
        // byte-limit symptom above because nothing surfaces to the user.
        // SshlibSftpConnector cranks both rekey thresholds as the mitigation;
        // when this probe fails, upstream fixed rekey — restore the defaults.
        val server = newServer()
        val client = sshlibClient {
            host = "127.0.0.1"; port = server.port
            rekeyIntervalMs = 1_000L
        }
        assertTrue(connectAndAuth(client) is ConnectResult.Success)
        runBlocking { kotlinx.coroutines.delay(2_500) } // ≥2 interval rekeys while idle
        val completed = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                runCatching { sftpRoundtripSuspend(client, Random(59).nextBytes(64 * 1024)) }
            }
        }
        assertTrue(
            "post-rekey SFTP now completes ($completed) — remove this GAP probe and restore " +
                "default rekey thresholds in SshlibSftpConnector (#58 P2)",
            completed == null || completed.isFailure,
        )
    }

    private fun <T> SftpResult<T>.expect(op: String): T = when (this) {
        is SftpResult.Success -> value
        is SftpResult.ServerError -> throw AssertionError("$op: server error $statusCode $message")
        is SftpResult.ProtocolError -> throw AssertionError("$op: protocol error $message")
        is SftpResult.IoError -> throw AssertionError("$op: io error ${cause}", cause)
    }

    private fun sftpRoundtrip(client: SshlibClient, payload: ByteArray) =
        runBlocking { sftpRoundtripSuspend(client, payload) }

    private suspend fun sftpRoundtripSuspend(client: SshlibClient, payload: ByteArray) {
        val sftp = client.openSftp().expect("openSftp")
        val write = sftp.open(
            "/roundtrip.bin",
            setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, SftpOpenFlag.TRUNCATE),
        ).expect("open-write")
        var off = 0
        while (off < payload.size) {
            val n = minOf(32 * 1024, payload.size - off)
            sftp.write(write, off.toLong(), payload.copyOfRange(off, off + n)).expect("write @$off")
            off += n
        }
        sftp.close(write)
        val read = sftp.open("/roundtrip.bin", setOf(SftpOpenFlag.READ)).expect("open-read")
        val out = java.io.ByteArrayOutputStream()
        var pos = 0L
        while (true) {
            val chunk = sftp.read(read, pos, 32 * 1024).expect("read @$pos") ?: break
            out.write(chunk)
            pos += chunk.size
        }
        sftp.close(read)
        sftp.close()
        assertArrayEquals(payload, out.toByteArray())
    }

    // ------------------------------------------------------------------
    // Keyboard-interactive multi-prompt shape (TOTP flow — phase 8)
    // ------------------------------------------------------------------

    @Test
    fun `PASS keyboard-interactive delivers multi-prompt challenges with echo flags`() {
        val server = newServer {
            keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                override fun generateChallenge(
                    session: ServerSession, username: String, lang: String, subMethods: String,
                ) = InteractiveChallenge().apply {
                    interactionName = "Login"
                    interactionInstruction = "Two factors required"
                    addPrompt("Password:", false)
                    addPrompt("OTP code:", true)
                }

                override fun authenticate(
                    session: ServerSession, username: String, responses: List<String>,
                ) = responses == listOf("secret", "123456")
            }
        }
        val client = sshlibClient { host = "127.0.0.1"; port = server.port }
        assertTrue(runBlocking { client.connect() } is ConnectResult.Success)

        var seenName = ""
        var seenPrompts: List<KeyboardInteractiveCallback.Prompt> = emptyList()
        val result = runBlocking {
            client.authenticateKeyboardInteractive(
                "tester",
                object : KeyboardInteractiveCallback {
                    override suspend fun onInfoRequest(
                        name: String,
                        instruction: String,
                        prompts: List<KeyboardInteractiveCallback.Prompt>,
                        respond: suspend (responses: List<String>) -> Unit,
                    ) {
                        seenName = name
                        seenPrompts = prompts
                        respond(listOf("secret", "123456"))
                    }
                },
            )
        }
        assertEquals(AuthResult.Success, result)
        assertEquals("Login", seenName)
        assertEquals(listOf("Password:", "OTP code:"), seenPrompts.map { it.text })
        assertEquals(listOf(false, true), seenPrompts.map { it.echo })
    }

    // ------------------------------------------------------------------
    // OpenSSH certificates (user certs #134/#185, host-CA #133)
    // ------------------------------------------------------------------

    @Test
    fun `GAP no certificate surface in the public auth API — flips when upstream adds one`() {
        // User-cert auth needs the client to send a *-cert-v01@openssh.com blob
        // in the publickey method. sshlib 0.3.1 exposes no way in: no public
        // method or parameter mentions certificates. When this fails, upstream
        // grew a cert path — unlock the cert slice of phase 8.
        val certMentions = listOf(
            org.connectbot.sshlib.SshClient::class.java,
            org.connectbot.sshlib.AuthHandler::class.java,
            org.connectbot.sshlib.SshKeys::class.java,
        ).flatMap { cls ->
            cls.methods.filter { m ->
                "cert" in m.name.lowercase() ||
                    m.parameterTypes.any { "cert" in it.simpleName.lowercase() }
            }
        }
        assertTrue("cert API appeared: $certMentions", certMentions.isEmpty())
    }

    @Test
    fun `GAP cert host-key algorithms cannot be negotiated — flips when upstream adds them`() {
        val server = newServer()
        val client = sshlibClient {
            host = "127.0.0.1"; port = server.port
            hostKeyAlgorithms = "ssh-ed25519-cert-v01@openssh.com"
        }
        val result = connectAndAuth(client)
        assertFalse(
            "cert host-key algo now negotiable — revisit host-CA (#133) support in phase 8",
            result is ConnectResult.Success,
        )
    }

    // ------------------------------------------------------------------
    // Exec exit status (phase 3)
    // ------------------------------------------------------------------

    @Test
    fun `GAP exec exit status is not surfaced — flips when a release carries cbssh PR 232`() {
        // sshlib runs exec fine but drops the RFC 4254 §6.10 exit-status
        // report, so ExecResult.exitStatus cannot be produced honestly.
        // Upstreamed as connectbot/cbssh#232 (SshSession.exitInfo). When a
        // release carries it, this fails: add SshlibExecContractTest and the
        // engine-aware exec routing (#58 P3).
        val exitMembers = org.connectbot.sshlib.SshSession::class.java.methods
            .filter { "exit" in it.name.lowercase() }
        assertTrue("exit API appeared: $exitMembers", exitMembers.isEmpty())
    }

    // ------------------------------------------------------------------
    // TransportFactory pluggability + jump chains (phase 7)
    // ------------------------------------------------------------------

    @Test
    fun `PASS custom socket-backed TransportFactory carries a full session`() {
        val server = newServer()
        val client = SshlibClient(
            SshClientConfig {
                hostKeyVerifier = trustAll
                transportFactory = TransportFactory {
                    val socket = Socket("127.0.0.1", server.port)
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    object : Transport {
                        override val isConnected: Boolean get() = socket.isConnected && !socket.isClosed
                        override suspend fun read(count: Int): ByteArray =
                            ByteArray(count).also { input.readFully(it) }
                        override suspend fun write(data: ByteArray) {
                            output.write(data); output.flush()
                        }
                        override suspend fun close() { socket.close() }
                    }
                }
            },
        ).also { clients.add(it) }
        assertTrue(connectAndAuth(client) is ConnectResult.Success)
        runBlocking {
            val sftp = (client.openSftp() as SftpResult.Success).value
            assertTrue((sftp.realpath(".") as SftpResult.Success).value.startsWith("/"))
            sftp.close()
        }
    }

    @Test
    fun `PASS openDirectTcpipTransport chains a jump hop end-to-end`() {
        val jumpHost = newServer {
            // MINA rejects direct-tcpip by default; a jump host must allow it.
            forwardingFilter = org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE
        }
        val target = newServer()

        val hop1 = sshlibClient { host = "127.0.0.1"; port = jumpHost.port }
        assertTrue(connectAndAuth(hop1) is ConnectResult.Success)

        val viaJump = hop1.openDirectTcpipTransport("127.0.0.1", target.port)
        assertNotNull("openDirectTcpipTransport returned null on an authed session", viaJump)

        val hop2 = SshlibClient(viaJump!!, trustAll).also { clients.add(it) }
        val hop2Result = connectAndAuth(hop2)
        if (hop2Result is ConnectResult.TransportError) hop2Result.cause.printStackTrace()
        assertTrue("second hop over direct-tcpip failed: $hop2Result", hop2Result is ConnectResult.Success)
        runBlocking {
            val sftp = (hop2.openSftp() as SftpResult.Success).value
            assertTrue((sftp.realpath(".") as SftpResult.Success).value.startsWith("/"))
            sftp.close()
        }
    }

    // ------------------------------------------------------------------
    // Agent forwarding (phase 8) — shallow probe only
    // ------------------------------------------------------------------

    @Test
    fun `SHALLOW agent forwarding can be enabled without breaking the session`() {
        // Proves enableAgentForwarding() is callable pre-connect and the
        // session still works. Does NOT prove end-to-end signing over the
        // forwarded channel — MINA has no easy server-side agent consumer;
        // that e2e lands with the phase-8 auth-parity work.
        val server = newServer()
        val client = sshlibClient { host = "127.0.0.1"; port = server.port }
        client.enableAgentForwarding(
            object : org.connectbot.sshlib.AgentProvider {
                override suspend fun getIdentities(): List<org.connectbot.sshlib.AgentIdentity> = emptyList()
                override suspend fun signData(context: org.connectbot.sshlib.AgentSigningContext): ByteArray? = null
            },
        )
        assertTrue(connectAndAuth(client) is ConnectResult.Success)
        runBlocking {
            val sftp = (client.openSftp() as SftpResult.Success).value
            assertTrue((sftp.realpath(".") as SftpResult.Success).value.isNotEmpty())
            sftp.close()
        }
    }
}
