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

    /**
     * #425: advertise EGFX H.264/AVC420. On by default (device-verified against
     * KRDP, which speaks only H.264). Advertising it switches the EGFX caps from
     * V10/AVC-disabled to V8.1/AVC420 for every server; Windows/xrdp still render
     * (AVC420 + ClearCodec/Planar/Progressive, all decoded) but Windows drops
     * from AVC444 to AVC420. Users can turn it off in Settings → Diagnostics.
     */
    @Volatile
    @JvmField
    var avcEnabled: Boolean = true
}
