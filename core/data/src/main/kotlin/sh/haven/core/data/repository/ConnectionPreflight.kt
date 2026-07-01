package sh.haven.core.data.repository

import sh.haven.core.data.db.entities.ConnectionProfile

/**
 * Runs just before [ConnectionProfile] is dialed, so a profile that depends on
 * transient app-layer state can restore it first. Introduced for the #287
 * "USB: …" bookmarks: opening a USB drive in the on-device VM saves a
 * `usbDriveSerial`-tagged profile pointing at that VM's loopback SSH port, but
 * the VM is torn down on eject/sleep/app-restart — so the bookmark's host:port
 * goes dead. [beforeConnect] re-opens the VM for the bookmarked drive (if it's
 * still attached) and returns the profile with its port/key refreshed, instead
 * of leaving the user to click a connection that always fails.
 *
 * No-op for every profile without special handling (`Result.Proceed` with the
 * profile unchanged) — this only intercepts the small set of profile "kinds"
 * that need it. Lives in `core/data` (not `app`) so the feature-layer connect
 * flow — which cannot depend on `app` — can trigger app-layer setup; the real
 * implementation is bound there (see `UsbDriveConnectionPreflight`).
 */
interface ConnectionPreflight {
    suspend fun beforeConnect(profile: ConnectionProfile): Result

    sealed interface Result {
        /** Dial [profile] (possibly refreshed) as normal. */
        data class Proceed(val profile: ConnectionProfile) : Result

        /** Don't dial; show [message] instead (e.g. "plug the drive back in"). */
        data class Block(val message: String) : Result
    }
}
