package sh.haven.app.usb

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.local.QemuManager
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbDeviceInfo
import sh.haven.core.usb.UsbIpServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the user-facing "Open USB drive" flow (#287): export the
 * attached USB mass-storage device over the shipped [UsbIpServer], boot the
 * [QemuManager] appliance VM (which imports + mounts it), and surface the
 * drive's files through an ordinary loopback SSH/SFTP [ConnectionProfile] — so
 * the existing file browser, terminal, and MCP file verbs all work unchanged.
 *
 * The VM boot is slow (TCG, no KVM unrooted), so [open] does the fast checks
 * (USB permission, mass-storage class) synchronously and then boots in the
 * background; callers poll [status]. One drive at a time.
 *
 * The saved "USB: …" connection is a durable **bookmark** (tagged with the
 * drive's serial via [ConnectionProfile.usbDriveSerial]), not a live session —
 * the VM behind it stops on [close]/sleep/app-restart, at which point the
 * profile's host:port goes dead. [reopenForProfile] (driven by
 * [UsbDriveConnectionPreflight]) reboots the VM and refreshes the profile the
 * moment the user clicks that bookmark again, instead of leaving a connection
 * that just fails.
 */
@Singleton
class UsbDriveVmManager @Inject constructor(
    private val qemuManager: QemuManager,
    private val usbIpServer: UsbIpServer,
    private val usbBroker: UsbBroker,
    private val connectionRepository: ConnectionRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val sshSessionManager: sh.haven.core.ssh.SshSessionManager,
) {
    enum class Phase { IDLE, OPENING, READY, ERROR }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val deviceName: String? = null,
        val productName: String? = null,
        val busid: String? = null,
        val profileId: String? = null,
        val keyId: String? = null,
        val mounts: List<String> = emptyList(),
        val sshPort: Int = 0,
        /** Human-readable progress while [phase] is OPENING (the boot is slow). */
        val stage: String = "",
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Update the progress line, but only while still opening (ignore late callbacks). */
    private fun stage(text: String) {
        _status.update { if (it.phase == Phase.OPENING) it.copy(stage = text) else it }
    }

    class UsbVmException(message: String) : Exception(message)

    /** USB mass-storage devices attached to the phone (the "open in a VM" candidates). */
    fun massStorageDevices(): List<UsbDeviceInfo> =
        usbBroker.listDevices().filter { it.interfaces.any { i -> i.interfaceClass == USB_CLASS_MASS_STORAGE } }

    /**
     * Validate + open the device + start the VM boot in the background. Returns
     * the resolved deviceName immediately once the (fast) checks pass; the VM is
     * still booting — poll [status] until phase READY (profileId set) or ERROR.
     */
    suspend fun open(deviceName: String?): String {
        if (_status.value.phase == Phase.OPENING || _status.value.phase == Phase.READY) {
            throw UsbVmException("A USB drive is already open in a VM; close it first.")
        }
        val target = resolveDrive(deviceName)
        val info = try {
            usbBroker.openDevice(target)
        } catch (e: Exception) {
            throw UsbVmException("USB open failed: ${e.message}")
        }
        if (info.interfaces.none { it.interfaceClass == USB_CLASS_MASS_STORAGE }) {
            throw UsbVmException("$target is not a USB mass-storage device (class 8). Use usb_attach_to_guest for HID/serial devices.")
        }
        val busid = busidOf(target)
        _status.value = Status(Phase.OPENING, target, info.productName, busid, stage = "Preparing…")
        scope.launch {
            try {
                val (profile, mounts) = bootAndAttach(target, info, busid, existingProfile = null)
                autoOpenInFiles(profile, mounts)
            } catch (e: Exception) {
                Log.w(TAG, "USB drive VM boot failed: ${e.message}")
                _status.value = Status(Phase.ERROR, target, info.productName, busid, error = e.message)
            }
        }
        return target
    }

    /**
     * Re-open the VM for an already-saved "USB: …" bookmark whose VM has
     * stopped — called by [UsbDriveConnectionPreflight] just before the
     * profile is dialed. Suspends until the drive is mounted + sshd answers
     * (or throws); the caller's own connect flow supplies the "connecting…"
     * UI, so this has no separate progress surface beyond [status] (also
     * visible via MCP `list_usb_drives`).
     *
     * Returns the profile with its `port`/`keyId` refreshed to the new VM
     * session (the ephemeral key is single-boot-scoped — a fresh one is
     * minted on every reopen). Caller is responsible for saving nothing
     * further; [bootAndAttach] already persisted the update.
     */
    suspend fun reopenForProfile(profile: ConnectionProfile, deviceName: String): ConnectionProfile {
        if (_status.value.phase == Phase.OPENING) {
            throw UsbVmException("A USB drive is already booting; wait for it to finish.")
        }
        val info = try {
            usbBroker.openDevice(deviceName)
        } catch (e: Exception) {
            throw UsbVmException("USB open failed: ${e.message}")
        }
        val busid = busidOf(deviceName)
        _status.value = Status(Phase.OPENING, deviceName, info.productName, busid, stage = "Reopening the drive…")
        return try {
            bootAndAttach(deviceName, info, busid, existingProfile = profile).first
        } catch (e: Exception) {
            _status.value = Status(Phase.ERROR, deviceName, info.productName, busid, error = e.message)
            throw UsbVmException("Couldn't reopen the USB drive VM: ${e.message}")
        }
    }

    /**
     * The shared VM-boot core for both a fresh "Open USB drive…" tap
     * ([existingProfile] null → a new bookmark is created, tagged with the
     * drive's serial) and reopening a saved bookmark ([existingProfile] set →
     * its port/key are refreshed in place, preserving the row's id so every
     * other reference to it — Files tabs, workspaces — keeps working). Only
     * the freshly-minted ephemeral key is cleaned up on failure; an
     * [existingProfile]'s prior (still relevant until we succeed) key is left
     * alone until the new one is confirmed working.
     */
    private suspend fun bootAndAttach(
        deviceName: String,
        info: UsbDeviceInfo,
        busid: String,
        existingProfile: ConnectionProfile?,
    ): Pair<ConnectionProfile, List<String>> {
        qemuManager.ensureProvisionedAppliance(::stage)
        stage("Sharing the drive with the VM…")
        var keyId: String? = null
        try {
            usbIpServer.start(deviceName) // export on :3240 (binds all interfaces)
            val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, "Haven USB drive")
            val keyEntity = SshKey(
                label = "USB drive VM (ephemeral)",
                keyType = key.type.sshName,
                privateKeyBytes = key.privateKeyBytes,
                publicKeyOpenSsh = key.publicKeyOpenSsh,
                fingerprintSha256 = key.fingerprintSha256,
            )
            sshKeyRepository.save(keyEntity); keyId = keyEntity.id

            val session = qemuManager.openDrive(busid, key.publicKeyOpenSsh, onStage = ::stage)

            val oldKeyId = existingProfile?.keyId
            val profile = existingProfile?.copy(
                host = "127.0.0.1", port = session.sshPort, username = "root",
                authType = ConnectionProfile.AuthType.KEY, keyId = keyEntity.id,
            ) ?: ConnectionProfile(
                label = driveLabel(info),
                host = "127.0.0.1",
                port = session.sshPort,
                username = "root",
                authType = ConnectionProfile.AuthType.KEY,
                keyId = keyEntity.id,
                connectionType = "SSH",
                usbDriveSerial = info.serialNumber,
            )
            connectionRepository.save(profile) // upsert — works for both new and existing ids
            if (oldKeyId != null && oldKeyId != keyEntity.id) {
                runCatching { sshKeyRepository.delete(oldKeyId) }
            }

            _status.value = Status(
                phase = Phase.READY, deviceName = deviceName, productName = info.productName,
                busid = busid, profileId = profile.id, keyId = keyEntity.id,
                mounts = session.mounts, sshPort = session.sshPort,
            )
            Log.i(TAG, "USB drive VM ready: $deviceName → profile ${profile.id}, mounts ${session.mounts}")
            return profile to session.mounts
        } catch (e: Exception) {
            runCatching { qemuManager.closeDrive() }
            runCatching { usbIpServer.stop() }
            // Only the fresh key we just minted — an existingProfile's prior
            // key is still whatever it was before this attempt, untouched.
            keyId?.let { runCatching { sshKeyRepository.delete(it) } }
            throw e
        }
    }

    /**
     * Surface the drive into the Files browser. Two steps, because the file
     * browser opens an SFTP *channel* on an already-connected SSH session —
     * it never dials one (SshSessionManager.openSftpForProfile only returns a
     * CONNECTED session). So:
     *  1. ConnectProfile — establish the SSH session via the same path a
     *     Connections tap uses (route-through/auth all apply). This also
     *     gives the "terminal into the VM for free".
     *  2. once CONNECTED, NavigateToSftpPath — switch to Files, open the
     *     SFTP channel on that session, and land on the mount.
     * Without (1), NavigateToSftpPath lands on Files but the listing fails
     * with "Not connected". Only used for the explicit "Open USB drive…" tap
     * — reopening a bookmark via [reopenForProfile] lets the caller's own
     * connect flow (a normal Terminal-tab SSH connect) drive the UI instead.
     */
    private suspend fun autoOpenInFiles(profile: ConnectionProfile, mounts: List<String>) {
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.ConnectProfile(profile.id),
        )
        // Ceiling only — we navigate the instant CONNECTED arrives. Generous
        // so a slow VM's SSH handshake still lands the auto-open; if it does
        // time out the drive is still connected + shows in Files (this only
        // gates the convenience navigation).
        val connected = withTimeoutOrNull(90_000) {
            sshSessionManager.sessions.first { m ->
                m.values.any {
                    it.profileId == profile.id &&
                        it.status == sh.haven.core.ssh.SshSessionManager.SessionState.Status.CONNECTED
                }
            }
        }
        if (connected != null) {
            // ConnectProfile switches the pager to the new VM terminal tab
            // as the session connects. Wait a beat so the Files navigation
            // below lands last and wins, instead of being overridden back
            // to Terminal. ponytail: a small settle delay beats threading a
            // "don't-switch-to-terminal" flag through the whole connect path.
            kotlinx.coroutines.delay(1500)
            // Read the real mounts over the now-connected SSH channel —
            // robust, unlike scraping the transient serial console (kernel
            // console noise + slow enumeration made that unreliable). Update
            // the status and land the auto-open on the actual mount.
            val liveMounts = queryMountsViaSsh(profile.id)
            if (liveMounts.isNotEmpty() && liveMounts != mounts) {
                _status.value = _status.value.copy(mounts = liveMounts)
            }
            val target = liveMounts.singleOrNull() ?: mounts.singleOrNull() ?: "/mnt"
            agentUiCommandBus.emit(
                sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath(profile.id, target),
            )
        }
    }

    /**
     * Tear down: power off the VM, stop the export. The bookmarked "USB: …"
     * connection (and its now-stale key/port) is kept — clicking it again
     * re-opens the VM via [reopenForProfile] and refreshes both. A dead
     * profile between eject and the next click is the point of the bookmark.
     */
    suspend fun close() {
        val s = _status.value
        if (s.phase == Phase.IDLE) return
        runCatching { qemuManager.closeDrive() }
        runCatching { usbIpServer.stop() }
        _status.value = Status(Phase.IDLE)
    }

    /** True once the persistent USB-helper appliance has been provisioned. */
    val applianceProvisioned: Boolean get() = qemuManager.isApplianceProvisioned

    /**
     * Delete the persistent USB-helper appliance (the installed Alpine that
     * mounts drives). The next [open] re-provisions it (one-time, slow again).
     * Closes any live VM first.
     */
    suspend fun deleteAppliance() {
        runCatching { close() }
        qemuManager.deleteAppliance()
    }

    /**
     * Read the drive's mount points from /proc/mounts over the VM's SSH session
     * — the same reliable channel the file browser uses. Preferred over scraping
     * QemuManager's transient serial console (kernel console noise + slow, retried
     * enumeration made that miss the report). Returns [] if the client isn't up.
     */
    private suspend fun queryMountsViaSsh(profileId: String): List<String> {
        val client = sshSessionManager.getSshClientForProfile(profileId)
        if (client == null) { Log.w(TAG, "queryMountsViaSsh: no connected client for $profileId"); return emptyList() }
        // The mount can land a little AFTER the session connects (slow, retried
        // enumeration), so poll /proc/mounts for a short window rather than
        // reading once. Reliable (SSH channel), unlike serial scraping.
        repeat(20) { attempt ->
            val mounts = runCatching {
                client.execCommand("cat /proc/mounts").stdout.lineSequence()
                    .mapNotNull { line -> line.split(' ').getOrNull(1)?.takeIf { it.startsWith("/mnt/") } }
                    .distinct().toList()
            }.getOrElse { Log.w(TAG, "queryMountsViaSsh exec failed: ${it.message}"); emptyList() }
            if (mounts.isNotEmpty()) { Log.i(TAG, "queryMountsViaSsh: $mounts (attempt $attempt)"); return mounts }
            kotlinx.coroutines.delay(2000)
        }
        Log.w(TAG, "queryMountsViaSsh: no /mnt mount appeared within ~40s")
        return emptyList()
    }

    /** Attached mass-storage device whose serial matches [serial], if any (bookmark re-open lookup). */
    fun findAttachedBySerial(serial: String): UsbDeviceInfo? =
        massStorageDevices().firstOrNull { it.serialNumber == serial }

    private fun resolveDrive(deviceName: String?): String {
        if (!deviceName.isNullOrBlank()) return deviceName
        val drives = massStorageDevices()
        return when (drives.size) {
            0 -> throw UsbVmException("No USB mass-storage drive attached.")
            1 -> drives.single().deviceName
            else -> throw UsbVmException(
                "Multiple USB drives attached — pass deviceName. Found: ${drives.joinToString { it.deviceName }}",
            )
        }
    }

    private fun driveLabel(info: UsbDeviceInfo): String =
        info.productName?.takeIf { it.isNotBlank() }?.let { "USB: $it" } ?: "USB drive"

    // /dev/bus/usb/BBB/DDD → "B-D" (matches usbip + start_usbip_export).
    private fun busidOf(deviceName: String): String {
        val parts = deviceName.trimEnd('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val dev = parts.lastOrNull()?.toIntOrNull() ?: 1
        return "$bus-$dev"
    }

    companion object {
        private const val TAG = "UsbDriveVmManager"
        const val USB_CLASS_MASS_STORAGE = 8
    }
}
