package sh.haven.core.ssh.sftp

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.ssh.SshIoException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/**
 * Engine-agnostic contract for [SftpSession] (#58): every test here runs
 * against a real Apache MINA sshd + SFTP subsystem on loopback, once per
 * engine via the concrete subclasses ([JschSftpSessionContractTest],
 * [SshlibSftpSessionContractTest]). A behavioural difference between the
 * JSch and sshlib engines fails the same test on one side only — this is
 * the parity gate later phases extend.
 */
abstract class SftpSessionContractTest {

    protected lateinit var server: SshServer
    protected var serverPort: Int = 0
    protected lateinit var root: Path
    private var session: SftpSession? = null

    /** Dial + authenticate + open an SFTP session on the engine under test. */
    protected abstract fun openSession(host: String, port: Int, username: String, password: String): SftpSession

    @Before
    fun startServer() {
        root = Files.createTempDirectory("sftp-contract")
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hostkey", ".ser"))
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }
        server.start()
        serverPort = server.port
    }

    @After
    fun stopServer() {
        try { session?.close() } catch (_: Exception) { /* best effort */ }
        if (::server.isInitialized) server.stop(true)
        root.toFile().deleteRecursively()
    }

    protected fun session(): SftpSession {
        session?.let { return it }
        return openSession("127.0.0.1", serverPort, "tester", "secret").also { session = it }
    }

    // --- list ---

    @Test
    fun `list returns entries and filters dot dirs`() = runBlocking {
        Files.write(root.resolve("a.txt"), byteArrayOf(1, 2, 3))
        Files.write(root.resolve("b.txt"), byteArrayOf(4))
        Files.createDirectory(root.resolve("sub"))

        val seen = mutableMapOf<String, SftpAttrs>()
        session().list("/") { attrs ->
            seen[attrs.filename] = attrs
            ListResult.CONTINUE
        }
        assertEquals(setOf("a.txt", "b.txt", "sub"), seen.keys)
        assertTrue(seen.getValue("sub").isDirectory)
        assertFalse(seen.getValue("a.txt").isDirectory)
        assertEquals(3L, seen.getValue("a.txt").size)
    }

    @Test
    fun `list BREAK stops enumeration early`() = runBlocking {
        repeat(5) { Files.write(root.resolve("f$it.txt"), byteArrayOf(0)) }
        var count = 0
        session().list("/") {
            count++
            ListResult.BREAK
        }
        assertEquals(1, count)
    }

    // --- stat ---

    @Test
    fun `stat reports size, type and permissions rendering`() = runBlocking {
        Files.write(root.resolve("data.bin"), ByteArray(1234))
        val file = session().stat("/data.bin")
        assertEquals(1234L, file.size)
        assertFalse(file.isDirectory)
        assertEquals("data.bin", file.filename)
        assertTrue("permissions '${file.permissions}'", file.permissions.startsWith("-"))

        Files.createDirectory(root.resolve("d"))
        assertTrue(session().stat("/d").isDirectory)
    }

    @Test
    fun `stat of a missing path throws SshIoException`(): Unit = runBlocking {
        assertThrows(SshIoException::class.java) {
            runBlocking { session().stat("/does-not-exist") }
        }
        Unit
    }

    // --- transfer ---

    @Test
    fun `upload overwrite then download roundtrips byte-exact with sane progress`() = runBlocking {
        // > 2 chunks so the loop logic is actually exercised
        val payload = Random(42).nextBytes(100_000)
        val uploadTicks = mutableListOf<Pair<Long, Long>>()
        session().upload(
            ByteArrayInputStream(payload), payload.size.toLong(), "/up.bin",
            SftpWriteMode.OVERWRITE,
        ) { transferred, total -> uploadTicks.add(transferred to total) }

        assertArrayEquals(payload, Files.readAllBytes(root.resolve("up.bin")))
        assertEquals(0L, uploadTicks.first().first)
        assertEquals(payload.size.toLong(), uploadTicks.last().first)
        assertTrue(uploadTicks.zipWithNext().all { (a, b) -> a.first <= b.first })

        val out = ByteArrayOutputStream()
        val downloadTicks = mutableListOf<Long>()
        session().download("/up.bin", out) { transferred, _ -> downloadTicks.add(transferred) }
        assertArrayEquals(payload, out.toByteArray())
        assertEquals(payload.size.toLong(), downloadTicks.last())
    }

    @Test
    fun `upload RESUME continues an interrupted transfer byte-exact`() = runBlocking {
        val payload = Random(7).nextBytes(100_000)
        // Simulate an interrupted transfer: the first 40k already made it.
        Files.write(root.resolve("resume.bin"), payload.copyOf(40_000))

        session().upload(
            ByteArrayInputStream(payload), payload.size.toLong(), "/resume.bin",
            SftpWriteMode.RESUME,
        ) { _, _ -> }

        assertArrayEquals(payload, Files.readAllBytes(root.resolve("resume.bin")))
    }

    @Test
    fun `upload RESUME to a missing destination writes the whole file`() = runBlocking {
        val payload = Random(9).nextBytes(50_000)
        session().upload(
            ByteArrayInputStream(payload), payload.size.toLong(), "/fresh.bin",
            SftpWriteMode.RESUME,
        ) { _, _ -> }
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("fresh.bin")))
    }

    @Test
    fun `openInputStream honours the offset`() = runBlocking {
        val payload = "0123456789".repeat(10_000).toByteArray() // 100k, multi-chunk
        Files.write(root.resolve("stream.bin"), payload)

        session().openInputStream("/stream.bin", offset = 5).use { stream ->
            val tail = stream.readBytes()
            assertArrayEquals(payload.copyOfRange(5, payload.size), tail)
        }

        session().openInputStream("/stream.bin").use { stream ->
            assertArrayEquals(payload, stream.readBytes())
        }
    }

    // --- directory + file management ---

    @Test
    fun `mkdir rename rm rmdir lifecycle`() = runBlocking {
        val s = session()
        s.mkdir("/newdir")
        assertTrue(Files.isDirectory(root.resolve("newdir")))

        Files.write(root.resolve("newdir/x.txt"), byteArrayOf(1))
        s.rename("/newdir/x.txt", "/newdir/y.txt")
        assertTrue(Files.exists(root.resolve("newdir/y.txt")))
        assertFalse(Files.exists(root.resolve("newdir/x.txt")))

        s.rm("/newdir/y.txt")
        assertFalse(Files.exists(root.resolve("newdir/y.txt")))

        s.rmdir("/newdir")
        assertFalse(Files.exists(root.resolve("newdir")))
    }

    @Test
    fun `chmod changes the permission rendering`() = runBlocking {
        Files.write(root.resolve("perm.txt"), byteArrayOf(1))
        session().chmod("/perm.txt", 0x180) // 0600
        assertEquals("-rw-------", session().stat("/perm.txt").permissions)
    }

    @Test
    fun `home returns an absolute path`() = runBlocking {
        val home = session().home()
        assertTrue("home '$home'", home.startsWith("/"))
    }

    @Test
    fun `isConnected reflects close`() = runBlocking {
        val s = session()
        assertTrue(s.isConnected)
        s.close()
        assertFalse(s.isConnected)
    }
}
