package sh.haven.core.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.KnownTlsCertDao
import sh.haven.core.data.db.entities.KnownTlsCert

/**
 * TOFU semantics for [TlsCertVerifier]: first cert is new, a matching cert
 * is trusted, a different cert on the same host:port is flagged as changed
 * (the MITM signal that closes security-review criticals #1/#2).
 */
class TlsCertVerifierTest {

    private class FakeDao : KnownTlsCertDao {
        val store = mutableMapOf<Pair<String, Int>, KnownTlsCert>()
        override fun observeAll(): Flow<List<KnownTlsCert>> = throw NotImplementedError()
        override suspend fun getAll(): List<KnownTlsCert> = store.values.toList()
        override suspend fun findByHostPort(hostname: String, port: Int): KnownTlsCert? =
            store[hostname to port]
        override suspend fun upsert(cert: KnownTlsCert) {
            store[cert.hostname to cert.port] = cert
        }
        override suspend fun delete(cert: KnownTlsCert) {
            store.remove(cert.hostname to cert.port)
        }
        override suspend fun deleteByHostPort(hostname: String, port: Int) {
            store.remove(hostname to port)
        }
    }

    private val certA = byteArrayOf(1, 2, 3, 4)
    private val certB = byteArrayOf(9, 8, 7, 6)

    @Test
    fun `first cert is NewCert with fingerprint`() = runBlocking {
        val v = TlsCertVerifier(FakeDao())
        val r = v.verify("host", 5900, certA)
        assertTrue(r is TlsCertResult.NewCert)
        assertEquals(TlsCertVerifier.fingerprint(certA), (r as TlsCertResult.NewCert).sha256)
    }

    @Test
    fun `same cert after accept is Trusted`() = runBlocking {
        val dao = FakeDao()
        val v = TlsCertVerifier(dao)
        v.accept("host", 5900, TlsCertVerifier.fingerprint(certA))
        assertTrue(v.verify("host", 5900, certA) is TlsCertResult.Trusted)
    }

    @Test
    fun `different cert after accept is CertChanged`() = runBlocking {
        val dao = FakeDao()
        val v = TlsCertVerifier(dao)
        v.accept("host", 5900, TlsCertVerifier.fingerprint(certA))
        val r = v.verify("host", 5900, certB)
        assertTrue(r is TlsCertResult.CertChanged)
        r as TlsCertResult.CertChanged
        assertEquals(TlsCertVerifier.fingerprint(certA), r.stored)
        assertEquals(TlsCertVerifier.fingerprint(certB), r.presented)
    }

    @Test
    fun `port is part of the key`() = runBlocking {
        val dao = FakeDao()
        val v = TlsCertVerifier(dao)
        v.accept("host", 5900, TlsCertVerifier.fingerprint(certA))
        // Same host, different port: unknown → NewCert, not a false CertChanged.
        assertTrue(v.verify("host", 5901, certB) is TlsCertResult.NewCert)
    }

    @Test
    fun `fingerprint is lowercase hex sha256`() {
        val fp = TlsCertVerifier.fingerprint(byteArrayOf())
        // SHA-256 of empty input.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            fp,
        )
    }
}
