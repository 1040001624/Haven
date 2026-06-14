package sh.haven.app.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.local.InstalledApp

class AppWindowShortcutManagerTest {

    private fun app(name: String, exec: String, icon: String? = "/i/$name.png") =
        InstalledApp(name = name, exec = exec, iconPath = icon, categories = emptyList())

    @Test
    fun matchesByBinaryBasenameIgnoringFieldCodesAndPath() {
        val apps = listOf(
            app("GNU Image Manipulation Program", "/usr/bin/gimp %U"),
            app("Calculator", "galculator"),
        )
        // Command is the bare binary; .desktop Exec has an absolute path + %U.
        assertEquals("GNU Image Manipulation Program", AppWindowShortcutManager.matchIcon(apps, "gimp")?.name)
        // And the reverse: command carries a path, Exec is bare.
        assertEquals("Calculator", AppWindowShortcutManager.matchIcon(apps, "/usr/bin/galculator")?.name)
    }

    @Test
    fun fallsBackToExactNameWhenNoBinaryMatch() {
        val apps = listOf(app("Synaptic Package Manager", "synaptic-pkexec"))
        assertEquals(
            "Synaptic Package Manager",
            AppWindowShortcutManager.matchIcon(apps, "Synaptic Package Manager")?.name,
        )
    }

    @Test
    fun skipsAppsWithoutADecodableIcon() {
        val apps = listOf(app("Calculator", "galculator", icon = null))
        assertNull(AppWindowShortcutManager.matchIcon(apps, "galculator"))
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        val apps = listOf(app("Calculator", "galculator"))
        assertNull(AppWindowShortcutManager.matchIcon(apps, "inkscape"))
    }
}
