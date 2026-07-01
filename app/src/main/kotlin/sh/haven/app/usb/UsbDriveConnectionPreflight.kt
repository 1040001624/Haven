package sh.haven.app.usb

import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionPreflight
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes a "USB: …" connection bookmark (#287) click-to-reconnect instead of a
 * dead loopback port once its VM has stopped (eject, phone sleep, app
 * restart). Bound to [ConnectionPreflight] for [feature/connections] via
 * [UsbDriveConnectionPreflightModule].
 */
@Singleton
class UsbDriveConnectionPreflight @Inject constructor(
    private val usbDriveVmManager: UsbDriveVmManager,
    private val sshSessionManager: SshSessionManager,
) : ConnectionPreflight {
    override suspend fun beforeConnect(profile: ConnectionProfile): ConnectionPreflight.Result {
        val serial = profile.usbDriveSerial ?: return ConnectionPreflight.Result.Proceed(profile)

        // The VM already IS this profile's drive (READY or still booting).
        // Crucial for the fresh "Open USB drive…" path: UsbDriveVmManager's
        // own auto-open-in-Files emits AgentUiCommand.ConnectProfile right
        // after bootAndAttach succeeds, which routes back through this same
        // preflight — without this check it would try to reopen a VM that's
        // already open (and fail, since QemuManager refuses a second boot).
        val vmStatus = usbDriveVmManager.sessionForProfile(profile.id)
        if (vmStatus != null &&
            (vmStatus.phase == UsbDriveVmManager.Phase.READY || vmStatus.phase == UsbDriveVmManager.Phase.OPENING)
        ) {
            return ConnectionPreflight.Result.Proceed(profile)
        }

        // Already live (an SSH session for this profile is connected or
        // connecting) — nothing to reopen.
        val alreadyUp = sshSessionManager.sessions.value.values.any {
            it.profileId == profile.id &&
                (it.status == SshSessionManager.SessionState.Status.CONNECTED ||
                    it.status == SshSessionManager.SessionState.Status.CONNECTING)
        }
        if (alreadyUp) return ConnectionPreflight.Result.Proceed(profile)

        val device = usbDriveVmManager.findAttachedBySerial(serial)
            ?: return ConnectionPreflight.Result.Block(
                "This USB drive isn't plugged in right now — plug it back in to reopen it.",
            )

        return try {
            val refreshed = usbDriveVmManager.reopenForProfile(profile, device.deviceName)
            ConnectionPreflight.Result.Proceed(refreshed)
        } catch (e: UsbDriveVmManager.UsbVmException) {
            ConnectionPreflight.Result.Block(e.message ?: "Couldn't reopen the USB drive VM.")
        }
    }
}
