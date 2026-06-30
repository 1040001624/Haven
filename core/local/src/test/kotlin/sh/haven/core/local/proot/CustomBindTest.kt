package sh.haven.core.local.proot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CustomBind] parse/spec coverage (#301). The persistence layer stores
 * each bind as its proot `-b` spec string, so parse(spec) must round-trip.
 */
class CustomBindTest {

    @Test
    fun `host-only spec round-trips`() {
        val b = CustomBind.parse("/storage/emulated/0/Sync")
        assertEquals("/storage/emulated/0/Sync", b.host)
        assertEquals("", b.guest)
        assertEquals("/storage/emulated/0/Sync", b.spec())
    }

    @Test
    fun `host-and-guest spec round-trips`() {
        val b = CustomBind.parse("/storage/emulated/9/Docs:/mnt/docs")
        assertEquals("/storage/emulated/9/Docs", b.host)
        assertEquals("/mnt/docs", b.guest)
        assertEquals("/storage/emulated/9/Docs:/mnt/docs", b.spec())
    }

    @Test
    fun `blank guest collapses to host-only spec`() {
        assertEquals("/data/x", CustomBind("/data/x", "").spec())
        // guest == host is redundant — also collapses.
        assertEquals("/data/x", CustomBind("/data/x", "/data/x").spec())
    }

    @Test
    fun `first colon is the separator (paths have no colon)`() {
        // Absolute paths never contain ':', so splitting on the first colon
        // is unambiguous even for deep guest paths.
        val b = CustomBind.parse("/a/b/c:/x/y/z")
        assertEquals("/a/b/c", b.host)
        assertEquals("/x/y/z", b.guest)
    }
}
