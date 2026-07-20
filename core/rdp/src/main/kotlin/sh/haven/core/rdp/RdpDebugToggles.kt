package sh.haven.core.rdp

/**
 * Process-global debug toggles for the RDP path.
 *
 * [RdpSession] is constructed by hand at several call sites (desktop VM,
 * feature/rdp VM, session manager) and isn't in the DI graph, so a debug flag
 * that has to reach [RdpConfig] is simplest as a process-global read there.
 * The value is bridged in from a persisted preference by `HavenApp` (which
 * observes the pref and pushes it here, seeding on startup and updating live).
 *
 * #418: temporary while the RemoteFX-Progressive WBT_TILE_UPGRADE decode is
 * verified against real Windows captures. Remove this once the upgrade path is
 * verified and enabled unconditionally.
 */
object RdpDebugToggles {
    /** Enable WBT_TILE_UPGRADE refinement decoding in the native decoder. */
    @Volatile
    @JvmField
    var progressiveUpgrade: Boolean = false
}
