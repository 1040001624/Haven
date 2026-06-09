package sh.haven.core.mail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CP-2 (Stage 2a) — logic-level coverage of [ImapMailClient] that needs no live
 * server: the opaque message-id codec (the part most likely to break silently),
 * wrong-param rejection, and IMAP inbox detection. The live read/fetch/parse
 * path is proven against a real server by the CP-0 spike (same android-mail lib)
 * and device-verified end-to-end in CP-5.
 */
class ImapMailClientTest {

    @Test
    fun messageIdRoundTrips() {
        val cases = listOf(
            "INBOX" to 1L,
            "INBOX" to 9_999_999L,
            "Archive/2026" to 42L,           // hierarchy separator in the folder name
            "[Gmail]/All Mail" to 7L,        // spaces in the folder name
            "Sent Items" to 123L,
        )
        for ((folder, uid) in cases) {
            val (f, u) = ImapMailClient.decodeId(ImapMailClient.encodeId(folder, uid))
            assertEquals("folder for $folder/$uid", folder, f)
            assertEquals("uid for $folder/$uid", uid, u)
        }
    }

    @Test
    fun decodeRejectsMalformedIds() {
        for (bad in listOf("", "INBOX", "INBOX ", " 5", "INBOX abc")) {
            try {
                ImapMailClient.decodeId(bad)
                fail("expected rejection for malformed id: '$bad'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun loginRejectsNonImapParams() = runBlocking {
        try {
            ImapMailClient().login("s1", MailConnectParams.Proton(username = "u", password = "p"))
            fail("expected IllegalArgumentException for Proton params on the IMAP engine")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Imap"))
        }
    }

    @Test
    fun imapInboxIsDetectedByFullName() {
        assertTrue(MailFolder(id = "INBOX", name = "INBOX", type = 0).isInbox)
        assertTrue(MailFolder(id = "inbox", name = "inbox", type = 0).isInbox)
        assertTrue("Proton inbox id still works", MailFolder(id = "0", name = "Inbox", type = 3).isInbox)
        assertTrue(!MailFolder(id = "Archive", name = "Archive", type = 0).isInbox)
    }

    @Test
    fun recentSliceWindowsTheNewestMessages() {
        // 1-based message numbers 1=oldest .. count=newest.
        // First page: the most-recent `limit`.
        assertEquals(901..1000, ImapMailClient.recentSlice(count = 1000, limit = 100, offset = 0))
        // Second page ("Load older").
        assertEquals(801..900, ImapMailClient.recentSlice(count = 1000, limit = 100, offset = 100))
        // Final short page clamps the low end to 1 (the remaining oldest).
        assertEquals(1..50, ImapMailClient.recentSlice(count = 1000, limit = 100, offset = 950))
        // A folder smaller than a page returns all of it.
        assertEquals(1..50, ImapMailClient.recentSlice(count = 50, limit = 100, offset = 0))
    }

    @Test
    fun recentSliceReturnsNullWhenNothingToFetch() {
        assertNull("empty folder", ImapMailClient.recentSlice(count = 0, limit = 100, offset = 0))
        assertNull("offset == count", ImapMailClient.recentSlice(count = 1000, limit = 100, offset = 1000))
        assertNull("offset past end", ImapMailClient.recentSlice(count = 1000, limit = 100, offset = 5000))
        assertNull("non-positive limit", ImapMailClient.recentSlice(count = 1000, limit = 0, offset = 0))
    }
}
