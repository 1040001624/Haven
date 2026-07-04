package sh.haven.app.agent

import org.connectbot.terminal.AgentLine
import org.connectbot.terminal.AgentSemanticSegment
import org.connectbot.terminal.AgentSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTurnHeuristicsTest {

    private fun line(text: String, vararg segs: AgentSemanticSegment) =
        AgentLine(text = text, softWrapped = false, semanticSegments = segs.toList())

    private fun seg(type: String, promptId: Int, start: Int = 0, end: Int = 0) =
        AgentSemanticSegment(startCol = start, endCol = end, type = type, promptId = promptId, metadata = null)

    private fun snap(cursorRow: Int, vararg lines: AgentLine) = AgentSnapshot(
        rows = lines.size, cols = 80, cursorRow = cursorRow, cursorCol = 0,
        cursorVisible = true, terminalTitle = "", scrollbackSize = 0, lines = lines.toList(),
    )

    // ── osc133Idle ───────────────────────────────────────────────────────

    @Test
    fun osc133_atRest_cursorOnNewestPrompt_isIdle() {
        // $ ls ↵ output ↵ [D] ↵ new prompt (cursor here)
        val s = snap(
            3,
            line("$ ls", seg("PROMPT", 1, 0, 2), seg("COMMAND_INPUT", 1, 2, 4)),
            line("README.md"),
            line("", seg("COMMAND_FINISHED", 1)),
            line("$ ", seg("PROMPT", 2, 0, 2), seg("COMMAND_INPUT", 2, 2, 2)),
        )
        assertEquals(true, osc133Idle(s))
    }

    @Test
    fun osc133_commandRunning_cursorBelowPrompt_isBusy() {
        // $ sleep 5 ↵ — cursor moved to the next line, no FINISHED yet
        val s = snap(
            1,
            line("$ sleep 5", seg("PROMPT", 2, 0, 2), seg("COMMAND_INPUT", 2, 2, 9)),
            line(""),
        )
        assertEquals(false, osc133Idle(s))
    }

    @Test
    fun osc133_noSegments_returnsNull() {
        assertNull(osc133Idle(snap(0, line("plain text"), line("no integration"))))
    }

    @Test
    fun osc133_typedButNotSubmitted_isStillIdle() {
        // Text sitting at the prompt unsubmitted: cursor still on the prompt row
        val s = snap(
            0,
            line("$ ls -la", seg("PROMPT", 3, 0, 2), seg("COMMAND_INPUT", 3, 2, 8)),
        )
        assertEquals(true, osc133Idle(s))
    }

    @Test
    fun osc133_staleSegmentsAbove_runningReplBelow_isBusy() {
        // Shell ran `claude` (input recorded, never finished); Claude Code now
        // owns the screen and the cursor sits in its UI, not on the prompt row.
        val s = snap(
            3,
            line("$ claude", seg("PROMPT", 5, 0, 2), seg("COMMAND_INPUT", 5, 2, 8)),
            line("● Working on it"),
            line("✳ Simmering… (esc to interrupt)"),
            line("❯ "),
        )
        assertEquals(false, osc133Idle(s))
    }

    // ── busy / prompt / repl heuristics ─────────────────────────────────

    @Test
    fun busy_spinnerAndEscToInterrupt() {
        assertTrue(looksBusy(listOf("✳ Simmering… (esc to interrupt · 32s)")))
        assertTrue(looksBusy(listOf("· 12.4k tokens")))
        assertFalse(looksBusy(listOf("$ ls", "README.md", "? for shortcuts")))
    }

    @Test
    fun promptPresent_shellAndReplPrompts() {
        assertTrue(promptPresent(listOf("user@host:~$ ")))
        assertTrue(promptPresent(listOf("❯ ")))
        assertTrue(promptPresent(listOf("│ ❯ type here                    │")))
        assertFalse(promptPresent(listOf("downloading 42%", "still working")))
    }

    @Test
    fun promptPresent_promptAtTopOfBlankScreen() {
        // Fresh plain busybox shell: "# " on row 0, 23 blank rows below
        // (device-verified failure of the first cut).
        val screen = listOf("#" + " ".repeat(79)) + List(23) { " ".repeat(80) }
        assertTrue(promptPresent(screen))
    }

    @Test
    fun promptPresent_nulPaddedScreen() {
        // What the emulator ACTUALLY returns for a fresh shell: never-painted
        // cells are NUL, not space — and NUL is neither isBlank() nor trim()able
        // in Kotlin (device-verified failure of the second cut).
        val nulRow = "\u0000".repeat(80)
        val screen = listOf("~ #" + "\u0000".repeat(77)) + List(23) { nulRow }
        assertTrue(promptPresent(screen))
    }

    @Test
    fun agentRepl_nulPaddedScreen() {
        val nulRow = "\u0000".repeat(80)
        val screen = listOf("? for shortcuts" + "\u0000".repeat(65)) + List(20) { nulRow }
        assertTrue(looksLikeAgentRepl(screen))
    }

    @Test
    fun agentRepl_chromeAboveTrailingBlanks() {
        val screen = listOf("● hi", "│ ❯          │") + List(20) { "" }
        assertTrue(looksLikeAgentRepl(screen))
    }

    @Test
    fun agentRepl_detectedByChrome() {
        assertTrue(looksLikeAgentRepl(listOf("some output", "? for shortcuts")))
        assertTrue(looksLikeAgentRepl(listOf("✶ Thinking… (esc to interrupt)")))
        assertTrue(looksLikeAgentRepl(listOf("│ ❯                          │")))
        assertFalse(looksLikeAgentRepl(listOf("user@host:~$ ls", "README.md")))
    }

    // ── scrapeLastAgentBlock ─────────────────────────────────────────────

    @Test
    fun scrape_lastBulletBlockAboveInputBox() {
        val screen = listOf(
            "● First reply",
            "  details of first",
            "",
            "● Second reply",
            "  more detail",
            "╭──────────────────────────────╮",
            "│ ❯                            │",
            "╰──────────────────────────────╯",
            "  ? for shortcuts",
        )
        assertEquals("● Second reply\n  more detail", scrapeLastAgentBlock(screen))
    }

    @Test
    fun scrape_toolUseBullet() {
        val screen = listOf(
            "⏺ Ran ls",
            "  README.md",
            "❯ ",
        )
        assertEquals("⏺ Ran ls\n  README.md", scrapeLastAgentBlock(screen))
    }

    @Test
    fun scrape_emptyScreen_returnsNull() {
        assertNull(scrapeLastAgentBlock(listOf("", "  ", "")))
        assertNull(scrapeLastAgentBlock(emptyList()))
    }

    @Test
    fun scrape_onlyChrome_returnsNull() {
        assertNull(
            scrapeLastAgentBlock(
                listOf("╭────╮", "│ ❯  │", "╰────╯"),
            ),
        )
    }
}
