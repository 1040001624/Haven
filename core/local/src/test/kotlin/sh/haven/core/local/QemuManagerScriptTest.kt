package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [runtimeSetupScript] and [markerVersion] are pure string-building/parsing —
 * extracted top-level so they're testable without an Android Context. These
 * pin the shell fragments the LUKS (#324... er, #287 follow-on) and
 * writable-mount (Phase 2) work depends on: the ro/rw mount branch, the LUKS
 * skip-and-report branch, and the appliance marker's version-upgrade gate.
 */
class QemuManagerScriptTest {

    @Test
    fun `read-only script mounts ro with the noload fallback`() {
        val script = runtimeSetupScript(busid = "1-2", pubKey = "ssh-ed25519 AAAA test", readOnly = true)
        assertTrue(script.contains("mount -o ro \"\$p\" \"\$d\""))
        assertTrue(script.contains("mount -o ro,noload \"\$p\" \"\$d\""))
        assertFalse("read-only must never mount rw", script.contains("mount -o rw"))
    }

    @Test
    fun `writable script mounts rw with sync, no noload fallback`() {
        val script = runtimeSetupScript(busid = "1-2", pubKey = "ssh-ed25519 AAAA test", readOnly = false)
        assertTrue(script.contains("mount -o rw,sync \"\$p\" \"\$d\""))
        assertFalse("writable must not silently downgrade via the ro,noload fallback", script.contains("mount -o ro"))
    }

    @Test
    fun `LUKS partitions are skipped in the mount loop and reported separately`() {
        val script = runtimeSetupScript(busid = "1-2", pubKey = "ssh-ed25519 AAAA test", readOnly = true)
        assertTrue("must classify by blkid TYPE before mounting", script.contains("blkid -o value -s TYPE"))
        assertTrue("must skip crypto_LUKS in the mount loop", script.contains("[ \"\$t\" = crypto_LUKS ] && continue"))
        assertTrue("must report unmapped LUKS partitions as HVNLOCKED", script.contains("HVNLOCKED"))
    }

    @Test
    fun `busid and pubkey are embedded verbatim`() {
        val script = runtimeSetupScript(busid = "3-7", pubKey = "ssh-ed25519 AAAAtestkey user@host", readOnly = true)
        assertTrue(script.contains("usbip attach -r 10.0.2.2 -b 3-7"))
        assertTrue(script.contains("ssh-ed25519 AAAAtestkey user@host"))
    }

    @Test
    fun `markerVersion parses a numeric marker`() {
        val dir = Files.createTempDirectory("qemu-marker-test").toFile()
        val marker = File(dir, "usb_vm_appliance.img.ok").apply { writeText("3\n") }
        assertEquals(3, markerVersion(marker))
        dir.deleteRecursively()
    }

    @Test
    fun `markerVersion treats a pre-versioning marker as version 0`() {
        val dir = Files.createTempDirectory("qemu-marker-test").toFile()
        val marker = File(dir, "usb_vm_appliance.img.ok").apply { writeText("ok\n") }
        assertEquals(0, markerVersion(marker))
        dir.deleteRecursively()
    }

    @Test
    fun `markerVersion is 0 for a missing marker`() {
        val dir = Files.createTempDirectory("qemu-marker-test").toFile()
        val marker = File(dir, "usb_vm_appliance.img.ok")
        assertEquals(0, markerVersion(marker))
        dir.deleteRecursively()
    }
}
