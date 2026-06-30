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
        val att = msg.attachments.first()
        assertEquals("doc.pdf", att.filename)
        assertEquals(0, att.index)
        assertEquals("application/pdf", att.mimeType)
        assertFalse(att.isInline)
        // "JVBERi0xLjQK" decodes to the 9-byte "%PDF-1.4\n".
        assertEquals(9L, att.sizeBytes)

        val extracted = MimeParser.extractAttachment(raw, 0)
        assertEquals("doc.pdf", extracted.filename)
        assertEquals("application/pdf", extracted.mimeType)
        assertEquals("%PDF-1.4\n", String(extracted.bytes))
    }

    @Test
    fun `parses Cc recipients for reply-all`() {
        val raw = """
            From: Alice <alice@proton.me>
            To: Bob <bob@example.com>
            Cc: Carol <carol@example.com>, dave@example.org
            Subject: Cc test
            Date: Wed, 03 Jun 2026 10:00:00 +0000
            Content-Type: text/plain; charset=utf-8

            Body.
        """.trimIndent().replace("\n", "\r\n").toByteArray()

        val msg = MimeParser.parse(raw)
        assertEquals(2, msg.cc.size)
        assertTrue(msg.cc.any { it.contains("carol@example.com") })
        assertTrue(msg.cc.any { it.contains("dave@example.org") })
    }

    @Test
    fun `indexes parts in dfs order, flags inline cid, round-trips exact bytes`() {
        val raw = """
            From: a@example.com
            Subject: Two parts
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="MIX"

            --MIX
            Content-Type: text/plain; charset=utf-8

            Body line.
            --MIX
            Content-Type: image/png
            Content-Disposition: inline
            Content-ID: <logo@haven>
            Content-Transfer-Encoding: base64

            aGVsbG8=
            --MIX
            Content-Type: text/csv; name="data.csv"
            Content-Disposition: attachment; filename="data.csv"

            a,b,c
            --MIX--
        """.trimIndent().replace("\n", "\r\n").toByteArray()

        val msg = MimeParser.parse(raw)
        assertEquals("Body line.", msg.bodyText.trim())
        assertEquals(2, msg.attachments.size)

        val inline = msg.attachments[0]
        assertEquals(0, inline.index)
        assertTrue("inline png flagged inline", inline.isInline)
        assertEquals("logo@haven", inline.contentId)
        assertEquals("image/png", inline.mimeType)

        val csv = msg.attachments[1]
        assertEquals(1, csv.index)
        assertFalse(csv.isInline)
        assertEquals("data.csv", csv.filename)

        // "aGVsbG8=" decodes to "hello"; the csv text part round-trips its content.
        assertEquals("hello", String(MimeParser.extractAttachment(raw, 0).bytes))
        assertTrue(String(MimeParser.extractAttachment(raw, 1).bytes).contains("a,b,c"))
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun `extracting a missing attachment index throws`() {
        val raw = """
            From: a@example.com
            Subject: No attachments
            MIME-Version: 1.0
            Content-Type: text/plain; charset=utf-8

            Just text.
        """.trimIndent().replace("\n", "\r\n").toByteArray()

        MimeParser.extractAttachment(raw, 0)
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
