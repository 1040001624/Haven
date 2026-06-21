package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #254: desktops that "install" but never render. The post-install gate
 * checked only one binary ([ProotManager.DesktopEnvironment.verifyBinary]),
 * so a half-completed package install — verifyBinary present, the
 * start-command components (window manager / panel) missing — passed as
 * success and then showed a blank screen. [missingDesktopBinaries] is the
 * pure check the gate now runs over every component the start command
 * launches.
 */
class DesktopBinaryVerificationTest {

    private fun rootfsWith(vararg present: String): File {
        val root = Files.createTempDirectory("de-verify-test").toFile()
        for (rel in present) {
            File(root, rel).apply {
                parentFile?.mkdirs()
                writeText("#!/bin/sh\n")
            }
        }
        return root
    }

    @Test
    fun `no binaries missing when every component is present`() {
        val root = rootfsWith(
            "usr/bin/startxfce4", "usr/bin/xfwm4", "usr/bin/xfce4-panel", "usr/bin/xfdesktop",
        )
        val missing = missingDesktopBinaries(
            root, listOf("usr/bin/xfwm4", "usr/bin/xfce4-panel", "usr/bin/xfdesktop"),
        )
        assertTrue("expected none missing, got $missing", missing.isEmpty())
        forceDeleteRecursively(root)
    }

    @Test
    fun `partial install reports exactly the missing components`() {
        // The old single-binary check would pass here (startxfce4 exists),
        // masking that the panel + desktop never installed.
        val root = rootfsWith("usr/bin/startxfce4", "usr/bin/xfwm4")
        val missing = missingDesktopBinaries(
            root, listOf("usr/bin/xfwm4", "usr/bin/xfce4-panel", "usr/bin/xfdesktop"),
        )
        assertEquals(listOf("usr/bin/xfce4-panel", "usr/bin/xfdesktop"), missing)
        forceDeleteRecursively(root)
    }

    @Test
    fun `empty binary list is always satisfied`() {
        // Nested-Wayland DEs declare no extraBinaries — the gate must be a
        // no-op for them, never a spurious failure.
        val root = rootfsWith()
        assertTrue(missingDesktopBinaries(root, emptyList()).isEmpty())
        forceDeleteRecursively(root)
    }

    @Test
    fun `xfce4 extraBinaries name the components its start command launches`() {
        val xfce = ProotManager.DesktopEnvironment.XFCE4
        // startCommands = "xfwm4 & xfce4-panel & xfdesktop &"
        assertTrue("usr/bin/xfwm4" in xfce.extraBinaries)
        assertTrue("usr/bin/xfce4-panel" in xfce.extraBinaries)
        assertTrue("usr/bin/xfdesktop" in xfce.extraBinaries)
    }

    @Test
    fun `openbox extraBinaries name xterm and xsetroot`() {
        val openbox = ProotManager.DesktopEnvironment.OPENBOX
        // startCommands = "xsetroot -solid '#2e3440'; openbox & xterm &"
        assertTrue("usr/bin/xterm" in openbox.extraBinaries)
        assertTrue("usr/bin/xsetroot" in openbox.extraBinaries)
    }
}
