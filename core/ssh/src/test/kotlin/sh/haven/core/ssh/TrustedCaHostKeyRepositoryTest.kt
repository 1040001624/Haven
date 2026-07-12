package sh.haven.core.ssh

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [TrustedCaHostKeyRepository] (#133): the entries it
 * serves are exactly what JSch's OpenSshCertificateUtil looks for — marker
 * "@cert-authority", a wildcard host pattern, and the raw CA key blob.
 */
class TrustedCaHostKeyRepositoryTest {

    private val caLine =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC5f5s1TvDgT9/pbuMt2hRUPKFtRLCq4fcH/mv/7g5WiAGeTVvl8IjxR5YUqWpbpb/3LvqIvAj6zGeKOElCh2uVZrleupeYbR9Qz1+gYopanxTUYV/eBGTIvdEOLRtxs1diBny2MLHRpuis16ggEDAwEVl0BlhV3Ti3fygx9oyUhS6BjxpeQm7gfdD+tacw4wPZJRTZ/SFrJEgtftRtpM/n0tVc0KzjL7wu3wS7Rf86zqSUxA8jDKboP/oE5lrJWpJqsNFaa8uqvak+kAbYVUkhEVmEuFd3J+02rccI40yaFWL+aUcytSry1JUFlBQWw7kcANAJP0mHxgVX9BtufSjJ haven-hostca-rsa-ca"

    @Test
    fun `typed public key line parses into a cert-authority wildcard entry`() {
        val repo = TrustedCaHostKeyRepository(listOf(caLine))
        assertEquals(1, repo.caCount)
        val hk = repo.hostKey.single()
        assertEquals("@cert-authority", hk.marker)
        assertEquals("*", hk.host)
        assertEquals("ssh-rsa", hk.type)
        assertEquals("AAAAB3NzaC1yc2EAAAADAQABAAABAQC5f5s1TvDgT9/pbuMt2hRUPKFtRLCq4fcH/mv/7g5WiAGeTVvl8IjxR5YUqWpbpb/3LvqIvAj6zGeKOElCh2uVZrleupeYbR9Qz1+gYopanxTUYV/eBGTIvdEOLRtxs1diBny2MLHRpuis16ggEDAwEVl0BlhV3Ti3fygx9oyUhS6BjxpeQm7gfdD+tacw4wPZJRTZ/SFrJEgtftRtpM/n0tVc0KzjL7wu3wS7Rf86zqSUxA8jDKboP/oE5lrJWpJqsNFaa8uqvak+kAbYVUkhEVmEuFd3J+02rccI40yaFWL+aUcytSry1JUFlBQWw7kcANAJP0mHxgVX9BtufSjJ", hk.key)
    }

    @Test
    fun `bare base64 blob also parses`() {
        val repo = TrustedCaHostKeyRepository(listOf("AAAAB3NzaC1yc2EAAAADAQABAAABAQC5f5s1TvDgT9/pbuMt2hRUPKFtRLCq4fcH/mv/7g5WiAGeTVvl8IjxR5YUqWpbpb/3LvqIvAj6zGeKOElCh2uVZrleupeYbR9Qz1+gYopanxTUYV/eBGTIvdEOLRtxs1diBny2MLHRpuis16ggEDAwEVl0BlhV3Ti3fygx9oyUhS6BjxpeQm7gfdD+tacw4wPZJRTZ/SFrJEgtftRtpM/n0tVc0KzjL7wu3wS7Rf86zqSUxA8jDKboP/oE5lrJWpJqsNFaa8uqvak+kAbYVUkhEVmEuFd3J+02rccI40yaFWL+aUcytSry1JUFlBQWw7kcANAJP0mHxgVX9BtufSjJ"))
        assertEquals(1, repo.caCount)
        assertEquals("@cert-authority", repo.hostKey.single().marker)
    }

    @Test
    fun `garbage lines are skipped without breaking valid ones`() {
        val repo = TrustedCaHostKeyRepository(listOf("", "not base64 at all !!!", caLine))
        assertEquals(1, repo.caCount)
    }

    @Test
    fun `check records consultation and never claims a match`() {
        val repo = TrustedCaHostKeyRepository(listOf(caLine))
        assertFalse(repo.checkConsulted)
        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("anyhost", ByteArray(8)))
        assertTrue(repo.checkConsulted)
    }

    @Test
    fun `repository is read-only`() {
        val repo = TrustedCaHostKeyRepository(listOf(caLine))
        repo.add(null, null)
        repo.remove("h", "t")
        assertEquals(1, repo.caCount)
    }
}
