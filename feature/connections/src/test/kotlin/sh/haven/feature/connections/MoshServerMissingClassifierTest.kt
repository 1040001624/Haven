package sh.haven.feature.connections

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #297: `moshServerLooksMissing` decides between the "install mosh" guide and
 * surfacing the real mosh-server stderr. The v5.60.7 heuristic matched a bare
 * "No such file", which reclassified the most common startup failure — the
 * locale error — as "not installed" and re-masked the very stderr that fix
 * existed to show.
 */
class MoshServerMissingClassifierTest {

    @Test
    fun `locale failure is NOT classified as missing`() {
        // The reporter's exact failure mode (dkoppenh, #297): mosh-server is
        // installed but refuses to start under a non-UTF-8 locale.
        val stderr = """
            locale: Cannot set LC_CTYPE to default locale: No such file or directory
            locale: Cannot set LC_ALL to default locale: No such file or directory
            The locale requested by LC_CTYPE=en_US.UTF-8 isn't available here.
            Running `locale-gen en_US.UTF-8' may be necessary.
            mosh-server needs a UTF-8 native locale to run.
        """.trimIndent()
        assertFalse(moshServerLooksMissing(exitStatus = 1, stderr = stderr))
    }

    @Test
    fun `exit 127 is missing`() {
        assertTrue(moshServerLooksMissing(exitStatus = 127, stderr = ""))
    }

    @Test
    fun `bash wording is missing`() {
        assertTrue(moshServerLooksMissing(1, "bash: mosh-server: command not found"))
    }

    @Test
    fun `zsh wording is missing`() {
        assertTrue(moshServerLooksMissing(1, "zsh: command not found: mosh-server"))
    }

    @Test
    fun `dash wording is missing`() {
        assertTrue(moshServerLooksMissing(1, "sh: 1: mosh-server: not found"))
    }

    @Test
    fun `exec path wording is missing`() {
        assertTrue(moshServerLooksMissing(1, "sh: /usr/bin/mosh-server: No such file or directory"))
    }

    @Test
    fun `mixed stderr with a locale line AND a real not-found line is missing`() {
        val stderr = """
            locale: Cannot set LC_CTYPE to default locale: No such file or directory
            bash: mosh-server: command not found
        """.trimIndent()
        assertTrue(moshServerLooksMissing(1, stderr))
    }

    @Test
    fun `empty stderr with non-127 exit is not missing`() {
        assertFalse(moshServerLooksMissing(1, ""))
    }
}
