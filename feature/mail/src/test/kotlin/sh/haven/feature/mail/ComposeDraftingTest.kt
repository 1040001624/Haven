package sh.haven.feature.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ComposeDrafting] — recipient parsing, subject prefixing,
 * address extraction, quoting, and reply/forward assembly. No Android, no live
 * server (mirrors [MimeParserTest]).
 */
class ComposeDraftingTest {

    private fun msg(
        subject: String = "Hello",
        from: String = "Alice <alice@example.com>",
        to: List<String> = emptyList(),
        body: String = "",
    ) = ParsedMessage(
        subject = subject,
        from = from,
        to = to,
        dateMillis = 0L,
        bodyText = body,
        bodyWasHtml = false,
        attachments = emptyList(),
    )

    @Test
    fun `parseRecipients splits on comma semicolon and whitespace and dedupes`() {
        val out = ComposeDrafting.parseRecipients("a@x, b@y; c@z  a@x\n d@w")
        assertEquals(listOf("a@x", "b@y", "c@z", "d@w"), out)
    }

    @Test
    fun `parseRecipients on blank input is empty`() {
        assertEquals(emptyList<String>(), ComposeDrafting.parseRecipients("   "))
    }

    @Test
    fun `ensureRePrefix adds once and is idempotent across case`() {
        assertEquals("Re: Hello", ComposeDrafting.ensureRePrefix("Hello"))
        assertEquals("Re: Hello", ComposeDrafting.ensureRePrefix("Re: Hello"))
        assertEquals("re: Hello", ComposeDrafting.ensureRePrefix("re: Hello"))
        assertEquals("RE: Hello", ComposeDrafting.ensureRePrefix("RE: Hello"))
        assertEquals("Re: ", ComposeDrafting.ensureRePrefix(""))
    }

    @Test
    fun `ensureFwdPrefix treats Fwd and Fw as already-prefixed`() {
        assertEquals("Fwd: Hello", ComposeDrafting.ensureFwdPrefix("Hello"))
        assertEquals("Fwd: Hello", ComposeDrafting.ensureFwdPrefix("Fwd: Hello"))
        assertEquals("fw: Hello", ComposeDrafting.ensureFwdPrefix("fw: Hello"))
    }

    @Test
    fun `extractAddress strips display name and angle brackets`() {
        assertEquals("a@x", ComposeDrafting.extractAddress("Alice <a@x>"))
        assertEquals("a@x", ComposeDrafting.extractAddress("<a@x>"))
        assertEquals("a@x", ComposeDrafting.extractAddress("  a@x  "))
    }

    @Test
    fun `quoteBody prefixes each line and keeps blank lines as bare gt`() {
        val out = ComposeDrafting.quoteBody("hello\n\nworld", "On D, A wrote:")
        assertEquals("\nOn D, A wrote:\n> hello\n>\n> world", out)
    }

    @Test
    fun `quoteBody normalises CRLF`() {
        val out = ComposeDrafting.quoteBody("a\r\nb", "H")
        assertEquals("\nH\n> a\n> b", out)
    }

    @Test
    fun `buildReply sender-only fills To and quotes body without Cc`() {
        val d = ComposeDrafting.buildReply(
            msg(subject = "Q", from = "Bob <bob@x>", to = listOf("me@x", "carol@y"), body = "hi"),
            replyAll = false,
            attributionLine = "On D, Bob wrote:",
            selfAddress = "me@x",
        )
        assertEquals("bob@x", d.to)
        assertEquals("", d.cc)
        assertFalse(d.showCcBcc)
        assertEquals("Re: Q", d.subject)
        assertEquals("\nOn D, Bob wrote:\n> hi", d.body)
    }

    @Test
    fun `buildReply replyAll adds other recipients to Cc minus self and To`() {
        val d = ComposeDrafting.buildReply(
            msg(from = "Bob <bob@x>", to = listOf("me@x", "Carol <carol@y>", "bob@x")),
            replyAll = true,
            attributionLine = "On D, Bob wrote:",
            selfAddress = "me@x",
        )
        assertEquals("bob@x", d.to)
        // self dropped, the To address (bob@x) dropped, display name stripped → just carol
        assertEquals("carol@y", d.cc)
        assertTrue(d.showCcBcc)
    }

    @Test
    fun `buildReply replyAll keeps self when selfAddress unknown`() {
        val d = ComposeDrafting.buildReply(
            msg(from = "Bob <bob@x>", to = listOf("me@x", "carol@y")),
            replyAll = true,
            attributionLine = "On D, Bob wrote:",
            selfAddress = null,
        )
        assertEquals("me@x, carol@y", d.cc)
    }

    @Test
    fun `buildForward has empty To Fwd subject header block and unquoted body`() {
        val d = ComposeDrafting.buildForward(
            msg(subject = "Q", from = "Bob <bob@x>", to = listOf("me@x"), body = "orig body"),
            forwardHeader = "---------- Forwarded message ----------",
        )
        assertEquals("", d.to)
        assertEquals("Fwd: Q", d.subject)
        assertTrue(d.body.contains("---------- Forwarded message ----------"))
        assertTrue(d.body.contains("From: Bob <bob@x>"))
        assertTrue(d.body.contains("To: me@x"))
        assertTrue(d.body.contains("Subject: Q"))
        assertTrue(d.body.contains("orig body"))
        // forwarded body is not quoted
        assertFalse(d.body.contains("> orig body"))
    }

    @Test
    fun `composeDraft isDirty reflects content`() {
        assertFalse(ComposeDraft().isDirty)
        assertTrue(ComposeDraft(subject = "x").isDirty)
        assertTrue(ComposeDraft(body = "x").isDirty)
    }
}
