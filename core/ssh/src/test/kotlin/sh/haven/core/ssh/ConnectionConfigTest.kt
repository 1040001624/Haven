package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigTest {

    @Test
    fun `constructor accepts valid config`() {
        val config = ConnectionConfig(host = "example.com", port = 22, username = "root")
        assertEquals("example.com", config.host)
        assertEquals(22, config.port)
        assertEquals("root", config.username)
    }

    @Test
    fun `constructor defaults port to 22`() {
        val config = ConnectionConfig(host = "example.com", username = "user")
        assertEquals(22, config.port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects blank host`() {
        ConnectionConfig(host = "", username = "user")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects blank username`() {
        ConnectionConfig(host = "example.com", username = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects port 0`() {
        ConnectionConfig(host = "example.com", port = 0, username = "user")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects port above 65535`() {
        ConnectionConfig(host = "example.com", port = 65536, username = "user")
    }

    @Test
    fun `constructor accepts port 1`() {
        val config = ConnectionConfig(host = "example.com", port = 1, username = "user")
        assertEquals(1, config.port)
    }

    @Test
    fun `constructor accepts port 65535`() {
        val config = ConnectionConfig(host = "example.com", port = 65535, username = "user")
        assertEquals(65535, config.port)
    }

    // Quick connect parsing

    @Test
    fun `parseQuickConnect handles user at host colon port`() {
        val config = ConnectionConfig.parseQuickConnect("root@192.168.1.1:2222")
        assertNotNull(config)
        assertEquals("root", config!!.username)
        assertEquals("192.168.1.1", config.host)
        assertEquals(2222, config.port)
    }

    @Test
    fun `parseQuickConnect handles user at host without port`() {
        val config = ConnectionConfig.parseQuickConnect("admin@server.example.com")
        assertNotNull(config)
        assertEquals("admin", config!!.username)
        assertEquals("server.example.com", config.host)
        assertEquals(22, config.port)
    }

    @Test
    fun `parseQuickConnect returns null for host without user`() {
        val config = ConnectionConfig.parseQuickConnect("example.com")
        assertNull(config)
    }

    @Test
    fun `parseQuickConnect returns null for blank input`() {
        assertNull(ConnectionConfig.parseQuickConnect(""))
        assertNull(ConnectionConfig.parseQuickConnect("   "))
    }

    @Test
    fun `parseQuickConnect trims whitespace`() {
        val config = ConnectionConfig.parseQuickConnect("  user@host:22  ")
        assertNotNull(config)
        assertEquals("user", config!!.username)
        assertEquals("host", config.host)
    }

    @Test
    fun `parseQuickConnect returns null for invalid port`() {
        assertNull(ConnectionConfig.parseQuickConnect("user@host:99999"))
    }

    @Test
    fun `parseQuickConnect handles IPv4 address`() {
        val config = ConnectionConfig.parseQuickConnect("deploy@10.0.0.1:8022")
        assertNotNull(config)
        assertEquals("10.0.0.1", config!!.host)
        assertEquals(8022, config.port)
    }

    // Auth method equality

    @Test
    fun `PrivateKey equals compares key bytes`() {
        val key1 = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1, 2, 3))
        val key2 = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1, 2, 3))
        val key3 = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(4, 5, 6))
        assertEquals(key1, key2)
        assert(key1 != key3)
    }

    // H3: passphrase is held as a CharArray so it can be zeroed.
    // These tests pin the contract that drove the security fix —
    // future refactors that revert PrivateKey.passphrase to String
    // (or any other immutable type) will fail compilation here.

    @Test
    fun `PrivateKey passphrase is a CharArray`() {
        val key = ConnectionConfig.AuthMethod.PrivateKey(
            keyBytes = byteArrayOf(1),
            passphrase = charArrayOf('s', 'e', 'c', 'r', 'e', 't'),
        )
        assertTrue(key.passphrase.contentEquals(charArrayOf('s', 'e', 'c', 'r', 'e', 't')))
    }

    @Test
    fun `PrivateKey String secondary constructor copies into CharArray`() {
        val key = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), "secret")
        assertTrue(key.passphrase.contentEquals(charArrayOf('s', 'e', 'c', 'r', 'e', 't')))
    }

    @Test
    fun `PrivateKey clear zeros the passphrase in place`() {
        val passphrase = charArrayOf('h', 'u', 'n', 't', 'e', 'r', '2')
        val key = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), passphrase)
        key.clear()
        // The same array the caller passed in is now zeroed — no residual
        // characters can be recovered from a heap dump after teardown.
        assertTrue(passphrase.all { it == '\u0000' })
        assertTrue(key.passphrase.all { it == '\u0000' })
    }

    @Test
    fun `PrivateKey equals compares passphrase content not reference`() {
        val a = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), charArrayOf('p', 'w'))
        val b = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), charArrayOf('p', 'w'))
        val c = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), charArrayOf('p', 'x'))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assert(a != c)
    }

    @Test
    fun `PrivateKey defaults to empty passphrase`() {
        val key = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1))
        assertEquals(0, key.passphrase.size)
    }

    @Test
    fun `PrivateKey copy() preserves data class semantics for SshSessionManager deep-copy`() {
        // SshSessionManager.getConnectionConfigForProfile() relies on
        // PrivateKey.copy(keyBytes = ...) to make a defensive copy of the
        // key material before handing it to a reconnect path. Pin that
        // the data class copy() is still generated.
        val original = ConnectionConfig.AuthMethod.PrivateKey(
            keyBytes = byteArrayOf(1, 2, 3),
            passphrase = charArrayOf('p', 'w'),
        )
        val duplicate = original.copy(keyBytes = original.keyBytes.copyOf())
        assertTrue(duplicate.keyBytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(duplicate.passphrase.contentEquals(charArrayOf('p', 'w')))
        // Mutating the copy's keyBytes must not affect the original.
        duplicate.keyBytes[0] = 0
        assertEquals(1.toByte(), original.keyBytes[0])
    }

    // M3: Password.clear() pre-existed but pin its contract for completeness.

    @Test
    fun `Password clear zeros the password CharArray`() {
        val pw = charArrayOf('a', 'b', 'c')
        val auth = ConnectionConfig.AuthMethod.Password(pw)
        auth.clear()
        assertTrue(pw.all { it == '\u0000' })
        assertTrue(auth.password.all { it == '\u0000' })
    }

    @Test
    fun `Password String secondary constructor stores a CharArray`() {
        val auth = ConnectionConfig.AuthMethod.Password("hunter2")
        assertTrue(auth.password.contentEquals(charArrayOf('h', 'u', 'n', 't', 'e', 'r', '2')))
    }

    // PrivateKeys auth method

    private fun keyEntry(label: String, bytes: ByteArray, cert: ByteArray? = null) =
        ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(label, bytes, cert)

    @Test
    fun `PrivateKeys holds single key pair`() {
        val keyBytes = byteArrayOf(1, 2, 3)
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(listOf(keyEntry("my-key", keyBytes)))
        assertEquals(1, auth.keys.size)
        assertEquals("my-key", auth.keys[0].label)
        assertTrue(auth.keys[0].keyBytes.contentEquals(keyBytes))
        assertNull(auth.keys[0].certificateBytes)
    }

    @Test
    fun `PrivateKeys holds multiple key pairs`() {
        val keys = listOf(
            keyEntry("work-key", byteArrayOf(1, 2, 3)),
            keyEntry("personal-key", byteArrayOf(4, 5, 6)),
            keyEntry("backup-key", byteArrayOf(7, 8, 9)),
        )
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(keys)
        assertEquals(3, auth.keys.size)
        assertEquals("work-key", auth.keys[0].label)
        assertEquals("personal-key", auth.keys[1].label)
        assertEquals("backup-key", auth.keys[2].label)
    }

    @Test
    fun `PrivateKeys preserves key byte content`() {
        val keyBytes = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x2D)
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(listOf(keyEntry("k", keyBytes)))
        assertTrue(auth.keys[0].keyBytes.contentEquals(keyBytes))
    }

    @Test
    fun `PrivateKeys carries an optional certificate per key`() {
        val keyBytes = byteArrayOf(1, 2, 3)
        val certBytes = byteArrayOf(9, 8, 7)
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(
            listOf(keyEntry("ca-key", keyBytes, certBytes)),
        )
        assertTrue(auth.keys[0].certificateBytes!!.contentEquals(certBytes))
        // Equality must account for the cert (#185).
        assertEquals(
            ConnectionConfig.AuthMethod.PrivateKeys(listOf(keyEntry("ca-key", keyBytes, certBytes))),
            auth,
        )
        assertNotEquals(
            ConnectionConfig.AuthMethod.PrivateKeys(listOf(keyEntry("ca-key", keyBytes))),
            auth,
        )
    }

    @Test
    fun `PrivateKeys with empty list holds no keys`() {
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(emptyList())
        assertEquals(0, auth.keys.size)
    }

    @Test
    fun `PrivateKeys is an AuthMethod`() {
        val auth: ConnectionConfig.AuthMethod =
            ConnectionConfig.AuthMethod.PrivateKeys(listOf(keyEntry("k", byteArrayOf(1))))
        assertTrue(auth is ConnectionConfig.AuthMethod.PrivateKeys)
    }

    @Test
    fun `ConnectionConfig accepts PrivateKeys as authMethod`() {
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(
            listOf(keyEntry("deploy", byteArrayOf(1, 2, 3)))
        )
        val config = ConnectionConfig(
            host = "example.com",
            username = "deploy",
            authMethod = auth,
        )
        assertEquals(auth, config.authMethod)
    }

    // Agent forwarding

    @Test
    fun `forwardAgent defaults to false`() {
        val config = ConnectionConfig(host = "example.com", username = "root")
        assert(!config.forwardAgent)
        assertEquals(0, config.agentIdentities.size)
    }

    @Test
    fun `forwardAgent carries agent identities`() {
        val keys = listOf(
            ConnectionConfig.AgentIdentity("work", byteArrayOf(1, 2, 3)),
            ConnectionConfig.AgentIdentity("personal", byteArrayOf(4, 5, 6)),
        )
        val config = ConnectionConfig(
            host = "example.com",
            username = "root",
            forwardAgent = true,
            agentIdentities = keys,
        )
        assertTrue(config.forwardAgent)
        assertEquals(2, config.agentIdentities.size)
        assertEquals("work", config.agentIdentities[0].label)
        assertTrue(config.agentIdentities[1].keyBytes.contentEquals(byteArrayOf(4, 5, 6)))
    }

    /**
     * #377: a passphrase-protected key added WITH its passphrase must come out
     * of JSch's identity repository already decrypted — ChannelAgentForwarding
     * silently skips identities still reporting isEncrypted(), which is
     * exactly how forwarded agents ended up empty for identity users.
     */
    @Test
    fun `encrypted agent identity with passphrase is decrypted at add time`() {
        val jsch = com.jcraft.jsch.JSch()
        jsch.addIdentity(
            "haven-agent-0-test377",
            ENCRYPTED_ED25519.toByteArray(),
            null,
            "haven-test-passphrase".toByteArray(),
        )
        val identity = jsch.identityRepository.identities.single()
        assertTrue("identity must be decrypted at add time or the forwarded agent drops it", !identity.isEncrypted)
    }

    @Test
    fun `encrypted agent identity without passphrase stays locked`() {
        // Documents WHY ConnectionsViewModel must filter these out: they load,
        // but stay encrypted, and ChannelAgentForwarding would silently skip them.
        val jsch = com.jcraft.jsch.JSch()
        jsch.addIdentity("haven-agent-0-test377", ENCRYPTED_ED25519.toByteArray(), null, null)
        val identity = jsch.identityRepository.identities.single()
        assertTrue(identity.isEncrypted)
    }

    private companion object {
        /** ssh-keygen -t ed25519 -N haven-test-passphrase — test fixture, not a real credential. */
        val ENCRYPTED_ED25519 = """
-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABDZSCfQdO
MOQtr8ANVZFB0SAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIJFbTzEDTGwb9Atz
ykIWCTn+/gY6Kon3303AB1HHI7JcAAAAoCGQ+UnOx0A9Jw5LJmhtOHWF++rRFfxtgW3wJZ
SSr8iEUHIXOdMPXoxeyqSUYbLXa+xRc8mp1zQA0xdCis/5amPS5aCS+/a/5GAzkBCxlTbm
nJwv0Zv4A3H9jYawIvx19LL8XoPmB0h0df1P/dmDLKcCNnokF2rg8uQ+zNAaWxTtxHq38x
CRDg6zju+yskI9WkrIrh1VNNSANZjsmEOdy1U=
-----END OPENSSH PRIVATE KEY-----
""".trimIndent() + "\n"
    }
}
