package sh.haven.feature.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses representative RFC822 blobs (the shape the mailbridge emits after
 * decrypting via proton.BuildRFC822) with Apache Mime4j. Pure JVM — no device,
 * no Proton account.
 */
class MimeParserTest {

    @Test
    fun `parses multipart alternative, prefers plain text, lists attachment`() {
        val raw = """
            From: Alice <alice@proton.me>
            To: Bob <bob@example.com>
            Subject: Hello Haven
            Date: Wed, 03 Jun 2026 10:00:00 +0000
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="MIX"

            --MIX
            Content-Type: multipart/alternative; boundary="ALT"

            --ALT
            Content-Type: text/plain; charset=utf-8

            Plain body line.
            --ALT
            Content-Type: text/html; charset=utf-8

            <html><body><p>HTML body</p></body></html>
            --ALT--
            --MIX
            Content-Type: application/pdf; name="doc.pdf"
            Content-Disposition: attachment; filename="doc.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --MIX--
        """.trimIndent().replace("\n", "\r\n").toByteArray()

        val msg = MimeParser.parse(raw)

        assertEquals("Hello Haven", msg.subject)
        assertTrue(msg.from.contains("alice@proton.me"))
        assertTrue(msg.to.any { it.contains("bob@example.com") })
        assertTrue("plain text preferred over html", msg.bodyText.contains("Plain body line."))
        assertFalse(msg.bodyWasHtml)
        assertEquals(1, msg.attachments.size)
        assertEquals("doc.pdf", msg.attachments.first().filename)
    }

    @Test
    fun `falls back to stripped html when no plain part`() {
        val raw = """
            From: news@example.com
            Subject: HTML only
            MIME-Version: 1.0
            Content-Type: text/html; charset=utf-8

            <html><body><p>Hello&nbsp;<b>world</b></p><script>evil()</script></body></html>
        """.trimIndent().replace("\n", "\r\n").toByteArray()

        val msg = MimeParser.parse(raw)

        assertEquals("HTML only", msg.subject)
        assertTrue(msg.bodyWasHtml)
        assertTrue(msg.bodyText.contains("Hello world"))
        assertFalse("script content must be stripped", msg.bodyText.contains("evil()"))
    }
}
