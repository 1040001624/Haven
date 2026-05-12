package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareAccessConfigBlobTest {

    @Test
    fun `encode-decode round-trips all fields`() {
        val blob = CloudflareAccessConfigBlob(
            hostname = "ssh.example.com",
            teamDomain = "myteam.cloudflareaccess.com",
            jwt = "eyJhbGciOiJSUzI1NiJ9.eyJleHAiOjE3Mzc5MzQ4MDB9.sig",
            jwtExpiresAt = 1737934800L,
        )
        val parsed = CloudflareAccessConfigBlob.parse(blob.encode())
        assertEquals("ssh.example.com", parsed.hostname)
        assertEquals("myteam.cloudflareaccess.com", parsed.teamDomain)
        assertEquals("eyJhbGciOiJSUzI1NiJ9.eyJleHAiOjE3Mzc5MzQ4MDB9.sig", parsed.jwt)
        assertEquals(1737934800L, parsed.jwtExpiresAt)
    }

    @Test
    fun `missing hostname is rejected`() {
        val bytes = """{"hostname":"","teamDomain":"x","jwt":"eyJ.eyJ.s"}""".toByteArray()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CloudflareAccessConfigBlob.parse(bytes)
        }
        assertTrue(ex.message!!.contains("hostname"))
    }

    @Test
    fun `missing jwt is rejected`() {
        val bytes = """{"hostname":"ssh.example.com","teamDomain":"x"}""".toByteArray()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CloudflareAccessConfigBlob.parse(bytes)
        }
        assertTrue(ex.message!!.contains("jwt"))
    }

    @Test
    fun `malformed JSON is rejected with a clear message`() {
        val bytes = "{not json".toByteArray()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CloudflareAccessConfigBlob.parse(bytes)
        }
        assertTrue(ex.message!!.contains("JSON"))
    }

    @Test
    fun `expired JWT is detected against a given now`() {
        val blob = CloudflareAccessConfigBlob(
            hostname = "ssh.example.com",
            teamDomain = "",
            jwt = "eyJ.eyJ.s",
            jwtExpiresAt = 1_000L,
        )
        assertTrue(blob.isJwtExpired(nowEpochSeconds = 2_000L))
        assertTrue(!blob.isJwtExpired(nowEpochSeconds = 500L))
    }

    @Test
    fun `zero expiry means no expiry tracking — never reports expired`() {
        // Pasted-JWT path where we couldn't parse the exp claim falls
        // back to 0; the UI shouldn't show a stale-pill on those.
        val blob = CloudflareAccessConfigBlob(
            hostname = "ssh.example.com",
            teamDomain = "",
            jwt = "opaque",
            jwtExpiresAt = 0L,
        )
        assertTrue(!blob.isJwtExpired(nowEpochSeconds = 9_999_999_999L))
    }
}
