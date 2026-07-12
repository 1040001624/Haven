package sh.haven.core.ssh

import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.keyprovider.FileHostKeyCertificateProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Full-path tests for host-CA trust (#133): a real Apache MINA sshd server
 * presents an OpenSSH HOST CERTIFICATE (ssh-rsa-cert-v01@openssh.com) and
 * [SshClient] connects with a [TrustedCaHostKeyRepository] installed —
 * exercising JSch's native @cert-authority verification end to end.
 *
 * The contract under test (SshClient.extractHostKey's fail-closed reading):
 *  - trusted CA configured  → connect returns null, hostVerifiedByCa = true;
 *  - wrong CA configured    → JSch falls back to plain-key verification and
 *    connect returns the STRIPPED underlying key for the normal TOFU flow;
 *  - no CA configured       → same fallback (today's behaviour, unchanged).
 *
 * The trusted CA is ECDSA-P256 while the host key is RSA — deliberate on
 * both counts: jsch 2.28.3 requires the cert's signature algorithm string
 * to EQUAL the CA key's algorithm ('rsa-sha2-512' vs 'ssh-rsa' never
 * match), so RSA host CAs don't validate natively (ed25519/ECDSA CAs do;
 * OpenSSH accepts both pairings — upstream jsch quirk, noted on #133);
 * and the JVM test environment has no EdDSA provider for MINA to parse an
 * ed25519 CA key inside the served cert, while ECDSA is JCE-native.
 *
 * Fixtures are checked in (generated once with ssh-keygen; validity runs to
 * 2286 so CI never hits expiry). MINA sshd is host-JVM only — see
 * [SshClientTofuTest] for why these live in src/test.
 */
class SshClientHostCaTest {

    private lateinit var server: SshServer
    private var serverPort: Int = 0
    private val tempFiles = mutableListOf<Path>()

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
        tempFiles.forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun connect_hostCertSignedByTrustedCa_skipsTofu() {
        startCertServer()
        val client = SshClient()
        try {
            val entry = runBlocking {
                client.connect(
                    config(),
                    connectTimeoutMs = 5_000,
                    trustedHostCaKeys = listOf(TRUSTED_CA_LINE),
                )
            }
            assertNull("CA-verified host must not surface a TOFU entry", entry)
            assertTrue(client.hostVerifiedByCa)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun connect_hostCertSignedByUntrustedCa_fallsBackToStrippedKeyTofu() {
        startCertServer()
        val client = SshClient()
        try {
            val entry = runBlocking {
                client.connect(
                    config(),
                    connectTimeoutMs = 5_000,
                    trustedHostCaKeys = listOf(WRONG_CA_LINE),
                )
            }
            assertNotNull("unknown CA must fall back to TOFU on the plain key", entry)
            // JSch strips the certificate to its underlying key on fallback.
            assertEquals("ssh-rsa", entry!!.keyType)
            assertTrue(entry.publicKeyBase64.isNotBlank())
            assertFalse(client.hostVerifiedByCa)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun connect_hostCertWithNoCaConfigured_behavesLikeToday() {
        startCertServer()
        val client = SshClient()
        try {
            val entry = runBlocking { client.connect(config(), connectTimeoutMs = 5_000) }
            assertNotNull(entry)
            assertEquals("ssh-rsa", entry!!.keyType)
            assertFalse(client.hostVerifiedByCa)
        } finally {
            client.disconnect()
        }
    }

    private fun config() = ConnectionConfig(
        host = "127.0.0.1",
        port = serverPort,
        username = "anyuser",
        authMethod = ConnectionConfig.AuthMethod.Password("accepted"),
    )

    /**
     * MINA sshd on loopback presenting HOST_CERT_LINE for HOST_PRIVATE_KEY.
     * With a HostKeyCertificateProvider set, MINA advertises the *-cert-v01
     * algorithm in KEX and JSch (whose defaults prefer cert algorithms)
     * negotiates the certificate.
     */
    private fun startCertServer() {
        val keyFile = tempFile("haven-hostca-key-", ".pem", HOST_PRIVATE_KEY)
        val certFile = tempFile("haven-hostca-cert-", ".pub", HOST_CERT_LINE + "\n")

        val keyPair = SecurityUtils.loadKeyPairIdentities(
            null,
            org.apache.sshd.common.NamedResource.ofName(keyFile.toString()),
            Files.newInputStream(keyFile),
            null,
        ).single()

        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = org.apache.sshd.common.keyprovider.KeyPairProvider.wrap(keyPair)
            hostKeyCertificateProvider = FileHostKeyCertificateProvider(certFile)
            passwordAuthenticator = AcceptAllPasswordAuthenticator.INSTANCE
        }
        server.start()
        serverPort = server.port
    }

    private fun tempFile(prefix: String, suffix: String, content: String): Path =
        Files.createTempFile(prefix, suffix).also {
            Files.writeString(it, content)
            it.toFile().deleteOnExit()
            tempFiles.add(it)
        }

    private companion object {
        /** RSA host private key (OpenSSH format) the test server presents. */
        private val HOST_PRIVATE_KEY = """
-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
NhAAAAAwEAAQAAAQEAxwFjL0QMq98CeSBHvSITCKPkUte//S53CwEpXM2i9XDGwYDJ6Wyg
Exo6eydql7Rbl2uk8Vc9wyka74JoUZohtm9xG5PtO+My/8m/5EyHvBJHgpGTtFBv0krO7f
57AUYOQoQAOa0ZVhbEGJVPSZsHzDzDDCPUw4L0pcpay8r4zxMFnOxAVrgMa6/gLU31RffI
SxDbE/S7jkqOmlmHw+1B2dsdotnEAeth1tfWD9pe7LzfV3pJM9ANrl8sawmf8jzVKxGRWc
6s1zRfyYTuPC0C1BQHZhMDX/FbdC1hfzau5faNSwTYnF0FyYsFb8EjtYph90yIo3Pw5Lfu
VJM81kxG2wAAA9D6ATjC+gE4wgAAAAdzc2gtcnNhAAABAQDHAWMvRAyr3wJ5IEe9IhMIo+
RS17/9LncLASlczaL1cMbBgMnpbKATGjp7J2qXtFuXa6TxVz3DKRrvgmhRmiG2b3Ebk+07
4zL/yb/kTIe8EkeCkZO0UG/SSs7t/nsBRg5ChAA5rRlWFsQYlU9JmwfMPMMMI9TDgvSlyl
rLyvjPEwWc7EBWuAxrr+AtTfVF98hLENsT9LuOSo6aWYfD7UHZ2x2i2cQB62HW19YP2l7s
vN9Xekkz0A2uXyxrCZ/yPNUrEZFZzqzXNF/JhO48LQLUFAdmEwNf8Vt0LWF/Nq7l9o1LBN
icXQXJiwVvwSO1imH3TIijc/Dkt+5UkzzWTEbbAAAAAwEAAQAAAQBJuoVdNxh8usBHUQLT
GyMzIclPOfi62/KlxJXm+JbF2TUB7u2fiiuaOC7vfrB6qQsABBUVhw1uVzXQA6ATj4eq+A
nPPlR3yhqUctIhyorFTn5o7PdsqBptrmKRASXgWGyjDxWoSK3o28FmHDdGXWYbsG1Q+5tx
nfx2ygwukbHxwYKiP3Kg+aj2Ig61N/FfQdqHXlQZSh9/DbIp9id4q/OGnaXSWXBYVC2Jcc
+W7QaRi42z32XdEGfQsULDM2HNCY55n7moKD7mU9g0C/cVvQLOUVvQhirLTBp5Rhkhg6IN
oi7PORyjviMk9ueapmlYPKk9P8U8GdUpFEHUpHTHk/PpAAAAgDsz2tiicK7s/6N9UCXa94
or808IBzAyuH46dPdj8oSS9gKfLf/zuY4J2ktYaJrbxRwAAZGhlg9utOJCjBJhFU6ugC7s
rBoEPfwONqylSpf1T/d5Qm06VAKmADKw3qJsG/hZjKeQW4UGJTtmZb7jOV1Sen7YOlHVqR
O4ZzdcRUynAAAAgQDkQpw1pDgqhhz/98TVwNB3/TFHLIQ6ip/G9hzJzSjQSXbAtARuq82g
MwpRHHZX8jeIfYHIRK8W+SCFZYmlepUXrP0VWmtFoGoL9DHazwv7tQhvyiv7si4nlrN+IA
OVSVa6g91TdvPjEHn97I1WPStmjbms1+gUnUO6CtrcwPgYIwAAAIEA3zCiMpQhoqC1hJl7
B+aVR1xrZmGqz7rTYrfUMwOGlqOrPJdJ1L7q9EoMUNJMhqdU8pF7xwxiTCKbE6KYfJGsNI
ldWjZflKEOGwwnZfalZnZOa6DWRJFHzfmBTnXdf0TTm6nBO/9NxBw7efzB7EgmibvlkRdG
HOPnVDr6OwBt5ekAAAAVaGF2ZW4taG9zdGNhLXJzYS1ob3N0AQIDBAUG
-----END OPENSSH PRIVATE KEY-----
""".trimIndent() + "\n"

        /**
         * Host certificate for HOST_PRIVATE_KEY: signed by TRUSTED_CA_LINE,
         * host cert type, principals localhost + 127.0.0.1, valid 2026-2286
         * (ssh-keygen -s rsa_ca -I hostca-rsa-test -h -n localhost,127.0.0.1
         *  -V 20260101:22860101).
         */
        private const val HOST_CERT_LINE =
            "ssh-rsa-cert-v01@openssh.com AAAAHHNzaC1yc2EtY2VydC12MDFAb3BlbnNzaC5jb20AAAAg674iromK6CuUKKS/QtIzd3OlkgNPa9B7RIiJou7N/UoAAAADAQABAAABAQDHAWMvRAyr3wJ5IEe9IhMIo+RS17/9LncLASlczaL1cMbBgMnpbKATGjp7J2qXtFuXa6TxVz3DKRrvgmhRmiG2b3Ebk+074zL/yb/kTIe8EkeCkZO0UG/SSs7t/nsBRg5ChAA5rRlWFsQYlU9JmwfMPMMMI9TDgvSlylrLyvjPEwWc7EBWuAxrr+AtTfVF98hLENsT9LuOSo6aWYfD7UHZ2x2i2cQB62HW19YP2l7svN9Xekkz0A2uXyxrCZ/yPNUrEZFZzqzXNF/JhO48LQLUFAdmEwNf8Vt0LWF/Nq7l9o1LBNicXQXJiwVvwSO1imH3TIijc/Dkt+5UkzzWTEbbAAAAAAAAAAAAAAACAAAADmhvc3RjYS1lYy10ZXN0AAAAGgAAAAlsb2NhbGhvc3QAAAAJMTI3LjAuMC4xAAAAAGlVuQAAAAACUmEVgAAAAAAAAAAAAAAAAAAAAGgAAAATZWNkc2Etc2hhMi1uaXN0cDI1NgAAAAhuaXN0cDI1NgAAAEEEgYhoo4QTjOlwckE2cBOJrLdd9+T69KV1+A5xju/J/JyNra5p1+EmB4AGNw+SZtIbuiA9TlXKUP5f5KCQcGzKCwAAAGUAAAATZWNkc2Etc2hhMi1uaXN0cDI1NgAAAEoAAAAhAOu0/XDlVaqQQQ7sXKYSV+sOF7WoFZsrWtK/B2iqPSOGAAAAIQCV2CmQZvUG+Y/j8xa3YA+3IhBesdNDIxxtlq05Vk4VSg== haven-hostca-rsa-host"

        /** The CA that signed HOST_CERT_LINE. */
        private const val TRUSTED_CA_LINE =
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBIGIaKOEE4zpcHJBNnATiay3Xffk+vSldfgOcY7vyfycja2uadfhJgeABjcPkmbSG7ogPU5VylD+X+SgkHBsygs= haven-hostca-ecdsa-ca"

        /** A different CA that did NOT sign the host cert. */
        private const val WRONG_CA_LINE =
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBB1W+DsqydbUEtMmqdu07TXfaDR1sRifpe+BD/JrJnbsPbDvVUs1jiimpPIcYd0KEzicR5G6EGT3DrDxZ3R0A2Y= haven-hostca-ecdsa-wrong-ca"
    }
}
