package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Test

/** The edit dialog's HavenSshEngine read/rewrite helpers (#58). */
class SshEngineOptionsTest {

    @Test
    fun `empty options read as JSCH`() {
        assertEquals("JSCH", sshEngineFromOptions(""))
        assertEquals("JSCH", sshEngineFromOptions("ServerAliveInterval 60"))
    }

    @Test
    fun `directive reads as SSHLIB, case-insensitive, both separators`() {
        assertEquals("SSHLIB", sshEngineFromOptions("HavenSshEngine sshlib"))
        assertEquals("SSHLIB", sshEngineFromOptions("havensshengine=SSHLIB"))
        assertEquals("SSHLIB", sshEngineFromOptions("ServerAliveInterval 60\nHavenSshEngine sshlib"))
    }

    @Test
    fun `commented directive is ignored`() {
        assertEquals("JSCH", sshEngineFromOptions("# HavenSshEngine sshlib"))
    }

    @Test
    fun `selecting SSHLIB appends the directive and preserves other options`() {
        assertEquals("HavenSshEngine sshlib", setSshEngineInOptions("", "SSHLIB"))
        assertEquals(
            "ServerAliveInterval 60\nHavenSshEngine sshlib",
            setSshEngineInOptions("ServerAliveInterval 60", "SSHLIB"),
        )
    }

    @Test
    fun `selecting JSCH removes the directive, round-trip is stable`() {
        val toggledOn = setSshEngineInOptions("ServerAliveInterval 60", "SSHLIB")
        assertEquals("ServerAliveInterval 60", setSshEngineInOptions(toggledOn, "JSCH"))
        // Toggling twice doesn't duplicate the line
        assertEquals(toggledOn, setSshEngineInOptions(toggledOn, "SSHLIB"))
    }
}
