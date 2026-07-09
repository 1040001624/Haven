package sh.haven.core.local.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.local.ProotManager

/**
 * The DE catalog lives in two places that must stay in lockstep: the legacy
 * [ProotManager.DesktopEnvironment] enum (what the UI and install paths key
 * on) and the [DesktopCatalog] spec list (what the launch dispatch reads via
 * `.spec`, which *throws* on a missing id). Adding an entry to one side only
 * — e.g. the Custom X11 DE (#361) — would compile fine and crash at first
 * render, so lock the parity here.
 */
class DesktopCatalogParityTest {

    @Test
    fun `every enum entry resolves to a catalog spec with the same id and label`() {
        ProotManager.DesktopEnvironment.entries.forEach { de ->
            val spec = DesktopCatalog.lookup(de.id)
            assertNotNull("enum ${de.name} (id=${de.id}) has no DesktopCatalog spec", spec)
            assertEquals("label mismatch for ${de.id}", de.label, spec!!.label)
        }
    }

    @Test
    fun `every catalog spec is reachable from the enum`() {
        val enumIds = ProotManager.DesktopEnvironment.entries.map { it.id }.toSet()
        DesktopCatalog.all.forEach { spec ->
            assertTrue("spec ${spec.id} has no DesktopEnvironment enum entry", spec.id in enumIds)
        }
    }

    @Test
    fun `custom X11 spec covers the same families as Openbox and installs an X server on each`() {
        // Openbox is the minimal X11Vnc DE — wherever it's offered, the
        // Custom command DE must be too (both need only Xvnc + a session).
        // Families neither declares (e.g. NIX) hide both from that distro.
        val custom = DesktopCatalog.lookup("custom-x11")!!
        assertEquals(
            "custom-x11 should be available on exactly the families Openbox is",
            DesktopCatalog.OPENBOX.packagesPerFamily.keys,
            custom.packagesPerFamily.keys,
        )
        custom.packagesPerFamily.forEach { (family, packages) ->
            assertTrue(
                "custom-x11 on $family must install a VNC X server",
                packages.any { it.startsWith("tigervnc") },
            )
        }
    }
}
