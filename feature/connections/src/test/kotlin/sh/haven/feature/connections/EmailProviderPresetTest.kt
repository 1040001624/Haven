package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmailProviderPresetTest {

    @Test
    fun fromServerDerivesNamedProviderFromImapHost() {
        assertEquals(EmailProviderPreset.GMAIL, EmailProviderPreset.fromServer("imap.gmail.com"))
        assertEquals(EmailProviderPreset.FASTMAIL, EmailProviderPreset.fromServer("imap.fastmail.com"))
        // Case- and whitespace-insensitive (a hand-typed host still resolves).
        assertEquals(EmailProviderPreset.GMAIL, EmailProviderPreset.fromServer("  IMAP.Gmail.COM "))
    }

    @Test
    fun fromServerFallsBackToGenericForUnknownOrBlank() {
        assertEquals(EmailProviderPreset.GENERIC, EmailProviderPreset.fromServer("imap.example.com"))
        assertEquals(EmailProviderPreset.GENERIC, EmailProviderPreset.fromServer(null))
        assertEquals(EmailProviderPreset.GENERIC, EmailProviderPreset.fromServer(""))
        assertEquals(EmailProviderPreset.GENERIC, EmailProviderPreset.fromServer("   "))
    }

    @Test
    fun genericCarriesNoServerCoordinatesNorAppPasswordLink() {
        val g = EmailProviderPreset.GENERIC
        assertNull(g.imapServer)
        assertNull(g.smtpServer)
        assertNull(g.appPasswordUrl)
    }

    @Test
    fun namedProvidersAreFullyPopulatedAndRoundTripViaFromServer() {
        EmailProviderPreset.entries
            .filter { it != EmailProviderPreset.GENERIC }
            .forEach { p ->
                assertNotNull("${p.name} imapServer", p.imapServer)
                assertNotNull("${p.name} smtpServer", p.smtpServer)
                assertNotNull("${p.name} appPasswordUrl", p.appPasswordUrl)
                // Each named provider's IMAP host resolves back to itself.
                assertEquals(p, EmailProviderPreset.fromServer(p.imapServer))
            }
    }
}
