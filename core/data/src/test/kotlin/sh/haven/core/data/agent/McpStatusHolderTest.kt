package sh.haven.core.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the live MCP activity tracking surfaced in the foreground
 * notification (#239): in-flight counting, call totals, and last-error
 * formatting.
 */
class McpStatusHolderTest {

    @Test
    fun `running flag toggles`() {
        val h = McpStatusHolder()
        assertFalse(h.activity.value.running)
        h.setRunning(true)
        assertTrue(h.activity.value.running)
        h.setRunning(false)
        assertFalse(h.activity.value.running)
    }

    @Test
    fun `concurrent calls increment and decrement inFlight`() {
        val h = McpStatusHolder()
        h.callStarted("a")
        h.callStarted("b")
        assertEquals(2, h.activity.value.inFlight)
        assertEquals(2, h.activity.value.callCount)
        assertEquals("b", h.activity.value.lastTool)
        h.callFinished("a")
        assertEquals(1, h.activity.value.inFlight)
        h.callFinished("b")
        assertEquals(0, h.activity.value.inFlight)
    }

    @Test
    fun `inFlight never goes below zero`() {
        val h = McpStatusHolder()
        h.callFinished("stray")
        assertEquals(0, h.activity.value.inFlight)
    }

    @Test
    fun `error is recorded as tool colon message and cleared by the next clean call`() {
        val h = McpStatusHolder()
        h.callStarted("run_in_proot")
        h.callFinished("run_in_proot", "boom")
        assertEquals("run_in_proot: boom", h.activity.value.lastError)
        // A subsequent successful call clears it — otherwise the always-on
        // FGS notification pins "MCP idle · last error: …" forever after one
        // malformed call, reading as a stuck error state (user-reported).
        h.callStarted("list_distros")
        h.callFinished("list_distros")
        assertNull(h.activity.value.lastError)
    }

    @Test
    fun `long error messages are truncated`() {
        val h = McpStatusHolder()
        h.callFinished("t", "x".repeat(500))
        // "t: " + 140 chars
        assertEquals(3 + 140, h.activity.value.lastError!!.length)
    }

    @Test
    fun `fresh holder has no activity`() {
        val a = McpStatusHolder().activity.value
        assertNull(a.lastTool)
        assertNull(a.lastError)
        assertEquals(0, a.callCount)
    }
}
