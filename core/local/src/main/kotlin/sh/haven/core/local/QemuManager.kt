package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.haven.core.local.proot.PackageFamily
import sh.haven.core.local.proot.PackageOps
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Runs on-device QEMU VMs that give phone-attached USB drives a **real
 * Linux kernel** — so mass-storage / block / ext4 / GPT, which proot has no
 * kernel for, work (#287). Each VM is the USB/IP *client* the proot guest
 * can't be (Android ships no vhci-hcd): it imports the device the shipped
 * [sh.haven.core.usb.UsbIpServer] exports, mounts it, and serves the files
 * over sshd. Haven points an ordinary loopback SSH/SFTP profile at it — no
 * new transport, no new UI; the drive's files appear in the normal file
 * browser. Up to [MAX_CONCURRENT_VMS] drives can be open at once, each its
 * own VM keyed by busid.
 *
 * Delivery = **serial-console auto-drive** (not an Alpine apkovl, which didn't
 * auto-apply from a separate disk reliably): qemu runs `-serial stdio`, so the
 * proot launcher Process's streams *are* the VM serial. We wait for `login:`,
 * send `root`, then one setup line (dhcp → apk add usbip+openssh → inject the
 * caller's pubkey → sshd → `usbip attach` → mount), then poll the forwarded
 * sshd port. This mirrors exactly what was proven by hand in the #287 spike.
 *
 * Unrooted Android = no /dev/kvm, so qemu runs TCG (slow but correct) — fine
 * for pulling files off a drive. Isochronous USB still can't pass (the broker
 * has no isochronous API).
 *
 * Orchestration note: the export (UsbIpServer) lives in `core:usb`, which this
 * module doesn't depend on, so the app layer starts the export and passes us
 * the `busid`; we only own the VM(s).
 */
@Singleton
class QemuManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    data class DriveSession(
        val busid: String,
        val sshPort: Int,
        val mounts: List<String>,
        val state: State,
        val error: String? = null,
        val readOnly: Boolean = true,
        /** Partitions found LUKS-encrypted (mount-dir name, e.g. "sdb2") and not yet unlocked. */
        val locked: List<String> = emptyList(),
    )

    // Live VM processes, keyed by busid — one per open drive. A busid's entry
    // exists here only while its VM process is actually running (openDrive
    // removes it on failure/close); [sessions] can retain a busid's last-known
    // state (e.g. State.ERROR) a little longer, for the UI to show.
    private val instances = ConcurrentHashMap<String, VmInstance>()

    private val _sessions = MutableStateFlow<Map<String, DriveSession>>(emptyMap())
    val sessions: StateFlow<Map<String, DriveSession>> = _sessions.asStateFlow()

    private fun updateSession(busid: String, transform: (DriveSession?) -> DriveSession) {
        _sessions.update { it + (busid to transform(it[busid])) }
    }

    // Provisioning/upgrading the shared appliance disk is a maintenance
    // operation on ONE file, unrelated to any particular busid — its own
    // dedicated VmInstance (not part of [instances]), serialized by
    // [provisioningMutex] so two drives opened at once (both needing to
    // provision/upgrade the still-shared appliance) don't race the same VM.
    private val provisioning = VmInstance()
    private val provisioningMutex = Mutex()

    /**
     * Boot a VM, import [busid] over USB/IP, mount its partitions (read-only
     * unless [readOnly] is false), and bring up sshd authorised for
     * [authorizedPubKey]. Returns the forwarded loopback ssh port + the
     * mounted paths (any LUKS-encrypted partition is left unmounted and
     * reported in [DriveSession.locked] instead — see [unlockPartition]).
     * Suspends through the (TCG) boot + one-time package install; throws on
     * failure (caller stops the export). Up to [MAX_CONCURRENT_VMS] drives
     * can be open at once — each phone-emulated VM is real RAM/CPU cost.
     */
    suspend fun openDrive(
        busid: String,
        authorizedPubKey: String,
        readOnly: Boolean = true,
        onStage: (String) -> Unit = {},
    ): DriveSession = withContext(Dispatchers.IO) {
        check(!instances.containsKey(busid)) { "A USB-drive VM for $busid is already running; close it first." }
        check(instances.size < MAX_CONCURRENT_VMS) {
            "Already running $MAX_CONCURRENT_VMS USB-drive VM(s) — that's the phone-resource limit (each is a full emulated machine). Close one first."
        }
        val instance = VmInstance()
        instances[busid] = instance
        updateSession(busid) { DriveSession(busid, 0, emptyList(), State.STARTING, readOnly = readOnly) }
        try {
            ensureQemu(onStage)
            val disk = ensureProvisionedAppliance(onStage)
            val port = freeLoopbackPort()

            onStage("Starting the virtual machine…")
            instance.start(qemuRuntimeCommand(disk, port)) { prootManager.startCommandInProot(it) }

            // 1) login — nudge getty until the prompt shows, then send root. The
            // appliance disk boots the already-installed system (no apk, no
            // re-download), so this is just a kernel boot — still TCG-slow and
            // load-dependent, so report live elapsed + a milestone.
            onStage("Booting the USB helper…")
            val bootOk = instance.awaitMarker("login:", BOOT_TIMEOUT_MS, nudge = true) { sec ->
                val hint = instance.bufferSnapshot().let { s ->
                    when {
                        s.contains("Welcome to Alpine") || s.contains("Alpine Linux") -> "kernel up, reaching login"
                        s.contains("SeaBIOS") || s.contains("ISOLINUX") -> "loading the kernel"
                        else -> "starting"
                    }
                }
                onStage("Booting Linux — $hint (${sec}s)…")
            }
            if (!bootOk) fail("VM didn't reach a login prompt within ${BOOT_TIMEOUT_MS / 1000}s — the emulated boot is slow and varies with phone load; try again, ideally with less else running.")
            instance.send("root\n")
            Thread.sleep(1500)
            // The tty echoes typed input back into the same stream awaitMarker/
            // sendAndAwaitRegex scan — since every script we send literally
            // contains its own marker text (e.g. "echo HAVEN_SETUP_DONE"), the
            // echo alone can satisfy the marker wait before the command has
            // even run. Kill echo once, right after login, for every script
            // sent for the rest of this session (setup, unlock, close).
            instance.send("stty -echo\n")
            Thread.sleep(300)
            // 2) one-shot setup; wait for the done marker.
            onStage("Setting up the VM and mounting your drive…")
            instance.send(runtimeSetupScript(busid, authorizedPubKey, readOnly) + "\n")
            val setupOk = instance.awaitMarker("HAVEN_SETUP_DONE", SETUP_TIMEOUT_MS) { sec ->
                onStage("Setting up the VM (installing drivers, mounting; ${sec}s)…")
            }
            if (!setupOk) fail("VM setup (attach/mount/sshd) didn't finish within ${SETUP_TIMEOUT_MS / 1000}s")
            // Snapshot the mounts NOW, the instant the marker arrives — the setup
            // script emits the HVNMOUNT/HVNLOCKED report right before
            // HAVEN_SETUP_DONE, so reading here (before the sshd-banner wait,
            // which gives kernel console output a window to trim the buffer)
            // captures it reliably.
            val mounts = instance.parseMounts()
            val locked = instance.parseLocked()
            // 3) confirm sshd actually answers on the forward.
            onStage("Almost ready — connecting…")
            if (!awaitSshBanner(port, SSH_TIMEOUT_MS)) fail("sshd never answered on 127.0.0.1:$port")

            DriveSession(busid, port, mounts, State.RUNNING, readOnly = readOnly, locked = locked)
                .also { session -> updateSession(busid) { session } }
        } catch (e: Exception) {
            Log.w(TAG, "openDrive($busid) failed: ${e.message}")
            updateSession(busid) { DriveSession(busid, 0, emptyList(), State.ERROR, e.message, readOnly = readOnly) }
            instance.stop()
            instances.remove(busid)
            throw e
        }
    }

    /**
     * Unlock a LUKS-encrypted partition reported in [DriveSession.locked] for
     * [busid]'s drive and mount it, against the *already-running* VM from
     * [openDrive] (no reboot — this is a follow-up command over the same
     * serial session). Returns the updated session; throws (wrong passphrase,
     * or the VM isn't running) without altering [sessions] so the caller can
     * re-prompt.
     */
    suspend fun unlockPartition(busid: String, devicePath: String, passphrase: String): DriveSession = withContext(Dispatchers.IO) {
        val instance = instances[busid] ?: fail("No USB-drive VM is running for $busid.")
        val current = _sessions.value[busid] ?: fail("No USB-drive VM is running for $busid.")
        check(instance.isAlive) { "The USB-drive VM for $busid isn't running." }
        val name = File(devicePath).name
        if (name !in current.locked) fail("$name isn't a locked partition on this drive.")
        // Passphrase piped via stdin (-d -), never as a cryptsetup argument (which
        // would leak it into `ps`); it does still transit the serial console into
        // the in-memory (never persisted) serial buffer, same trust model as
        // the ephemeral SSH key already sent the same way.
        // Mount matches the session's readOnly (the same rw+sync risk tradeoff
        // as the plain mount loop in runtimeSetupScript applies here too).
        val mapperMount = if (current.readOnly) {
            // Same non-ext4/xfs noload gotcha as runtimeSetupScript's mount loop.
            "t=\$(blkid -o value -s TYPE \"/dev/mapper/crypt_$name\" 2>/dev/null); " +
                "mount -o ro \"/dev/mapper/crypt_$name\" \"/mnt/$name\" 2>/dev/null || " +
                "if [ \"\$t\" = ext4 ] || [ \"\$t\" = xfs ]; then mount -o ro,noload \"/dev/mapper/crypt_$name\" \"/mnt/$name\" 2>/dev/null; fi"
        } else {
            "mount -o rw,sync \"/dev/mapper/crypt_$name\" \"/mnt/$name\" 2>/dev/null"
        }
        // Same report format runtimeSetupScript ends with — a full fresh
        // re-scan (not scoped to just this partition), so the mounts/locked
        // lists below stay accurate for every partition on the drive, not
        // just the one just unlocked.
        val reportScript = "for p in /dev/sd[a-z][0-9]*; do [ -b \"\$p\" ] || continue; l=\$(basename \"\$p\"); " +
            "t=\$(blkid -o value -s TYPE \"\$p\" 2>/dev/null); " +
            "[ \"\$t\" = crypto_LUKS ] && [ ! -e \"/dev/mapper/crypt_\$l\" ] && echo \"HVNLOCKED:\$l\"; done; " +
            "while read d m r; do case \"\$m\" in /mnt/*) echo \"HVNMOUNT:\$m\";; esac; done < /proc/mounts; "
        val script = "modprobe dm_crypt 2>/dev/null; " +
            "printf '%s' " + shellSingleQuote(passphrase) + " | cryptsetup luksOpen \"$devicePath\" \"crypt_$name\" -d - 2>/dev/null; " +
            "mkdir -p \"/mnt/$name\"; { $mapperMount; }; " +
            reportScript +
            // The real, final check — not the `&&` chain's short-circuit state,
            // which a stale/echoed marker match could otherwise satisfy without
            // ever confirming the mount actually happened (see sendAndAwaitRegex).
            "mountpoint -q \"/mnt/$name\" 2>/dev/null && echo HAVEN_UNLOCK_OK || echo HAVEN_UNLOCK_FAIL"
        val since = instance.bufferSnapshot().length
        val marker = instance.sendAndAwaitRegex(script, "HAVEN_UNLOCK_OK|HAVEN_UNLOCK_FAIL", UNLOCK_TIMEOUT_MS)
        val mounts = instance.parseMounts(since)
        val locked = instance.parseLocked(since)
        if (marker == null || marker == "HAVEN_UNLOCK_FAIL") fail("Wrong passphrase, or the partition failed to mount.")
        current.copy(mounts = mounts, locked = locked).also { session -> updateSession(busid) { session } }
    }

    /**
     * Power off + reap [busid]'s VM. Idempotent. The caller stops its USB/IP
     * export.
     *
     * Explicitly `sync`s and unmounts every mount under /mnt before poweroff,
     * and waits (bounded) for confirmation, rather than trusting the VM's own
     * shutdown scripts to finish before the force-kill ceiling below. This
     * matters most for a writable mount: racing a force-kill against an
     * in-flight write is exactly how you corrupt a filesystem. Best-effort —
     * a hung/already-dead VM just times out and falls through to the reap.
     */
    suspend fun closeDrive(busid: String): Unit = withContext(Dispatchers.IO) {
        val instance = instances[busid]
        if (instance?.isAlive == true) {
            val script = "sync; for m in /mnt/*; do [ -d \"\$m\" ] && mountpoint -q \"\$m\" 2>/dev/null && " +
                "umount \"\$m\" 2>/dev/null; done; sync; echo HAVEN_UNMOUNT_DONE"
            instance.sendAndAwaitRegex(script, "HAVEN_UNMOUNT_DONE", UNMOUNT_TIMEOUT_MS)
        }
        // Graceful poweroff next — harmless after the explicit unmount above,
        // and still lets networking/sshd shut down cleanly — then reap.
        runCatching { instance?.send("\npoweroff\n") }
        instance?.waitFor(4)
        instance?.stop()
        instances.remove(busid)
        _sessions.update { it - busid }
    }

    /** Close every currently-open drive VM (used before [deleteAppliance], which all of them depend on). */
    suspend fun closeAllDrives() {
        instances.keys.toList().forEach { closeDrive(it) }
    }

    // --- appliance provisioning -------------------------------------------

    // Provisioning boot: ISO + the blank appliance disk (virtio = /dev/vda).
    // No hostfwd — provisioning is serial-only (install Alpine to /dev/vda).
    private fun qemuProvisionCommand(isoGuestPath: String, diskGuestPath: String): String =
        "exec qemu-system-x86_64 -M pc -m $VM_MEM_MB -display none -monitor none " +
            "-serial stdio -no-reboot -boot d -cdrom $isoGuestPath " +
            "-drive file=$diskGuestPath,if=virtio,format=raw " +
            "-netdev user,id=n0 -device virtio-net-pci,netdev=n0"

    // Runtime boot: the provisioned appliance disk ONLY (no ISO, no re-install).
    // -boot c boots the installed system from /dev/vda; hostfwd exposes its sshd.
    private fun qemuRuntimeCommand(diskGuestPath: String, port: Int): String =
        // exec → the launcher process *is* qemu (clean to signal). Serial on
        // stdio = our Process streams; headless; -no-reboot so poweroff exits.
        "exec qemu-system-x86_64 -M pc -m $VM_MEM_MB -display none -monitor none " +
            "-serial stdio -no-reboot -boot c " +
            "-drive file=$diskGuestPath,if=virtio,format=raw " +
            "-netdev user,id=n0,hostfwd=tcp:127.0.0.1:$port-:22 -device virtio-net-pci,netdev=n0"

    private suspend fun ensureQemu(onStage: (String) -> Unit = {}) {
        onStage("Checking the VM engine…")
        val (out, _) = prootManager.runCommandInProot("command -v qemu-system-x86_64 || true")
        if (out.contains("qemu-system-x86_64")) return
        val family = prootManager.activeDistro.family
        val pkg = qemuPackageFor(family)
        val ops = PackageOps.forFamily(family)
        onStage("Installing the VM engine (one-time)…")
        Log.i(TAG, "installing $pkg (${family})")
        val (instOut, code) = prootManager.runCommandInProot("${ops.updateCmd()} >/dev/null 2>&1 ; ${ops.installCmd(listOf(pkg))} 2>&1")
        val (check, _) = prootManager.runCommandInProot("command -v qemu-system-x86_64 || true")
        if (!check.contains("qemu-system-x86_64")) {
            throw IllegalStateException("Could not install $pkg in ${prootManager.activeDistroId} (exit $code): ${instOut.takeLast(200)}")
        }
    }

    private fun qemuPackageFor(family: PackageFamily): String = when (family) {
        PackageFamily.APK -> "qemu-system-x86_64"
        PackageFamily.APT -> "qemu-system-x86"
        PackageFamily.PACMAN -> "qemu-system-x86"
        PackageFamily.XBPS -> "qemu"
        else -> "qemu"
    }

    /** Download + verify the appliance ISO once into cacheDir (bound at /tmp in proot). */
    private fun ensureAppliance(onStage: (String) -> Unit = {}): String {
        val dir = File(context.cacheDir, "haven-vm").apply { mkdirs() }
        val iso = File(dir, APPLIANCE_NAME)
        val marker = File(dir, "$APPLIANCE_NAME.ok")
        if (iso.exists() && marker.exists()) return "/tmp/haven-vm/$APPLIANCE_NAME"
        iso.delete(); marker.delete()
        onStage("Downloading the Linux image (one-time, ~270 MB)…")
        Log.i(TAG, "downloading appliance ISO …")
        val conn = URL(APPLIANCE_URL).openConnection() as java.net.HttpURLConnection
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: APPLIANCE_SIZE
        conn.inputStream.use { input ->
            iso.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var read = 0L; var lastPct = -1
                var n = input.read(buf)
                while (n >= 0) {
                    out.write(buf, 0, n); read += n
                    val pct = ((read * 100) / total).toInt()
                    if (pct >= lastPct + 5) { onStage("Downloading the Linux image (one-time)… $pct%"); lastPct = pct }
                    n = input.read(buf)
                }
            }
        }
        onStage("Verifying the download…")
        val sha = MessageDigest.getInstance("SHA-256").let { md ->
            iso.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16); var n = ins.read(buf)
                while (n >= 0) { md.update(buf, 0, n); n = ins.read(buf) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!sha.equals(APPLIANCE_SHA256, ignoreCase = true)) {
            iso.delete(); throw SecurityException("appliance ISO checksum mismatch: $sha")
        }
        marker.writeText("ok\n")
        return "/tmp/haven-vm/$APPLIANCE_NAME"
    }

    /** True once the persistent appliance disk has been provisioned. */
    val isApplianceProvisioned: Boolean
        get() = File(File(context.cacheDir, "haven-vm"), "$APPLIANCE_DISK.ok").exists()

    /**
     * Provision the persistent appliance disk once — install Alpine + the
     * extra package set to it (via `setup-disk -m sys`) — or return it
     * immediately if already provisioned. After this, every [openDrive] boots
     * the disk directly (no ISO, no apk, no network), so repeat opens are just
     * a kernel boot. The user keeps the appliance until they [deleteAppliance].
     * Serialized by [provisioningMutex] — with multiple drives able to open at
     * once, two callers could otherwise both see "not provisioned" and race
     * the same provisioning boot.
     *
     * The whole sequence (install + serial-console + passwordless-root config)
     * was validated locally under KVM; see scratch/qemu-appliance.
     *
     * Public so callers can provision BEFORE starting the USB/IP export — the
     * one-time provision takes minutes, and holding the export open across it
     * stales the drive (it re-imports at the wrong speed and never enumerates).
     * openDrive() calls this again, but it's a no-op once provisioned.
     */
    suspend fun ensureProvisionedAppliance(onStage: (String) -> Unit): String = provisioningMutex.withLock {
        val dir = File(context.cacheDir, "haven-vm").apply { mkdirs() }
        val disk = File(dir, APPLIANCE_DISK)
        val marker = File(dir, "$APPLIANCE_DISK.ok")
        val guestDisk = "/tmp/haven-vm/$APPLIANCE_DISK"
        if (disk.exists() && markerVersion(marker) == APPLIANCE_PROVISION_VERSION) return@withLock guestDisk
        if (disk.exists() && marker.exists()) {
            // Already provisioned, just missing a package added since (e.g.
            // cryptsetup for LUKS) — a much cheaper in-place upgrade boot than
            // a full ISO re-provision.
            upgradeProvisionedAppliance(guestDisk, onStage)
            marker.writeText("$APPLIANCE_PROVISION_VERSION\n")
            return@withLock guestDisk
        }

        // (Re-)provision from scratch. Needs the ISO; drop it again afterwards.
        disk.delete(); marker.delete()
        val iso = ensureAppliance(onStage)
        onStage("Building the USB helper Linux (one-time)…")
        Log.i(TAG, "provisioning appliance disk …")
        // Raw sparse image (truncate is universal — no qemu-img dependency).
        prootManager.runCommandInProot("rm -f $guestDisk && truncate -s $APPLIANCE_DISK_SIZE $guestDisk")
        provisioning.start(qemuProvisionCommand(iso, guestDisk)) { prootManager.startCommandInProot(it) }
        try {
            val bootOk = provisioning.awaitMarker("login:", BOOT_TIMEOUT_MS, nudge = true) { sec ->
                onStage("Building the USB helper Linux — booting (${sec}s)…")
            }
            if (!bootOk) fail("appliance provisioning: VM didn't reach a login prompt in ${BOOT_TIMEOUT_MS / 1000}s")
            provisioning.send("root\n"); Thread.sleep(1500)
            provisioning.send("stty -echo\n"); Thread.sleep(300) // see openDrive's comment
            onStage("Building the USB helper Linux — installing (one-time)…")
            provisioning.send(provisionScript + "\n")
            val ok = provisioning.awaitMarker("HAVEN_PROVISION_DONE", PROVISION_TIMEOUT_MS) { sec ->
                onStage("Building the USB helper Linux — installing (${sec}s)…")
            }
            if (!ok) fail("appliance provisioning didn't finish in ${PROVISION_TIMEOUT_MS / 1000}s")
        } finally {
            runCatching { provisioning.send("\npoweroff\n") }
            provisioning.waitFor(6)
            provisioning.stop()
        }
        // setup-disk grows the sparse image well past 20 MB; a tiny file means
        // the install never happened (marker can't gate that — the host file is
        // what we boot next).
        if (!disk.exists() || disk.length() < 20_000_000L) fail("appliance disk wasn't provisioned")
        marker.writeText("$APPLIANCE_PROVISION_VERSION\n")
        // The appliance is self-contained now; reclaim the ~270 MB ISO. Deleting
        // the appliance re-downloads + re-provisions.
        runCatching { File(dir, APPLIANCE_NAME).delete(); File(dir, "$APPLIANCE_NAME.ok").delete() }
        Log.i(TAG, "appliance provisioned (${disk.length() / 1024 / 1024} MB)")
        guestDisk
    }

    /** Delete the persistent appliance; the next [openDrive] re-provisions it. Closes every live drive VM first (they all depend on this one disk). */
    suspend fun deleteAppliance() {
        closeAllDrives()
        val dir = File(context.cacheDir, "haven-vm")
        val removed = File(dir, APPLIANCE_DISK).delete()
        File(dir, "$APPLIANCE_DISK.ok").delete()
        Log.i(TAG, "deleteAppliance: removed=$removed")
    }

    /** Boot the already-installed appliance disk just to `apk add` packages added since it was provisioned. */
    private fun upgradeProvisionedAppliance(guestDisk: String, onStage: (String) -> Unit) {
        onStage("Updating the USB helper Linux (one-time)…")
        Log.i(TAG, "upgrading appliance package set …")
        provisioning.start(qemuRuntimeCommand(guestDisk, freeLoopbackPort())) { prootManager.startCommandInProot(it) }
        try {
            val bootOk = provisioning.awaitMarker("login:", BOOT_TIMEOUT_MS, nudge = true) { sec ->
                onStage("Updating the USB helper Linux — booting (${sec}s)…")
            }
            if (!bootOk) fail("appliance upgrade: VM didn't reach a login prompt in ${BOOT_TIMEOUT_MS / 1000}s")
            provisioning.send("root\n"); Thread.sleep(1500)
            provisioning.send("stty -echo\n"); Thread.sleep(300) // see openDrive's comment
            onStage("Updating the USB helper Linux (one-time)…")
            // Re-installs the FULL extra-package set (not just what's new since
            // the caller's prior version) — simpler than tracking per-version
            // deltas, and `apk add` on an already-installed package is a cheap
            // no-op, so this costs nothing extra for anyone who's already current
            // on some of them.
            val script = "ip link set eth0 up; udhcpc -i eth0 -q -n 2>/dev/null; " +
                "apk update -q && apk add -q $APPLIANCE_EXTRA_PACKAGES && echo HAVEN_UPGRADE_OK || echo HAVEN_UPGRADE_FAIL"
            val marker = provisioning.sendAndAwaitRegex(script, "HAVEN_UPGRADE_OK|HAVEN_UPGRADE_FAIL", PROVISION_TIMEOUT_MS)
            if (marker != "HAVEN_UPGRADE_OK") fail("appliance upgrade (installing $APPLIANCE_EXTRA_PACKAGES) failed or timed out")
        } finally {
            runCatching { provisioning.send("\npoweroff\n") }
            provisioning.waitFor(6)
            provisioning.stop()
        }
    }

    // The one-time install line typed at the ISO's root shell with a blank
    // /dev/vda attached. Validated locally under KVM (scratch/qemu-appliance):
    // installs the live system to disk (sys mode), keeps console=ttyS0 +
    // passwordless serial root, and gates the done-marker on success so a failed
    // setup-disk can't be read as provisioned.
    private val provisionScript: String =
        "ip link set eth0 up; udhcpc -i eth0 -q -n 2>/dev/null; " +
            "printf 'https://dl-cdn.alpinelinux.org/alpine/v3.21/main\\n" +
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/community\\n' > /etc/apk/repositories; " +
            "apk update -q && apk add -q linux-tools-usbip openssh e2fsprogs syslinux $APPLIANCE_EXTRA_PACKAGES && " +
            "export ERASE_DISKS=/dev/vda BOOTLOADER=syslinux && " +
            "setup-disk -m sys -s 0 /dev/vda && " +
            "R=\$(blkid | grep /dev/vda | grep -i ext4 | head -1 | cut -d: -f1) && [ -n \"\$R\" ] && " +
            "mount \$R /mnt && chroot /mnt rc-update add sshd default 2>/dev/null; " +
            "if [ -d /mnt/etc ]; then chroot /mnt rc-update add networking default 2>/dev/null; " +
            "grep -q ttyS0 /mnt/etc/inittab || echo 'ttyS0::respawn:/sbin/getty -L 0 ttyS0 vt100' >> /mnt/etc/inittab; " +
            "for f in /mnt/boot/extlinux.conf /mnt/extlinux.conf; do [ -f \"\$f\" ] && " +
            "{ grep -q console=ttyS0 \"\$f\" || sed -i 's|\\(APPEND .*\\)|\\1 console=ttyS0,115200|' \"\$f\"; }; done; " +
            "printf 'auto eth0\\niface eth0 inet dhcp\\n' > /mnt/etc/network/interfaces; " +
            "mkdir -p /mnt/root/.ssh; chmod 700 /mnt/root/.ssh; sync; umount /mnt && echo HAVEN_PROVISION_DONE; fi"

    // --- misc internals ----------------------------------------------------

    private fun freeLoopbackPort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun fail(msg: String): Nothing = throw IllegalStateException(msg)

    /** Single-quote [s] for embedding in a POSIX shell command (handles embedded single quotes). */
    private fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun awaitSshBanner(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                    s.soTimeout = 2000
                    val b = ByteArray(4)
                    val n = s.getInputStream().read(b)
                    n >= 4 && String(b) == "SSH-"
                }
            }.getOrDefault(false)
            if (ok) return true
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    companion object {
        private const val TAG = "QemuManager"
        private const val VM_MEM_MB = 768
        // Each VM is a full emulated machine (VM_MEM_MB RAM + a TCG CPU core) —
        // a real phone-resource limit, not an arbitrary cap.
        const val MAX_CONCURRENT_VMS = 2
        private const val SERIAL_CAP = 256 * 1024
        // Generous: TCG boot (no KVM) is slow and varies a lot with phone load —
        // 4 min was marginal and timed out under load before reaching login.
        private const val BOOT_TIMEOUT_MS = 420_000L
        private const val SETUP_TIMEOUT_MS = 360_000L
        // How long (s) to wait INSIDE the VM for the drive to enumerate after
        // usbip attach — a ceiling, not a fixed sleep (we mount the instant it
        // appears). Generous so a slow CPU/large drive still makes it; stays
        // within SETUP_TIMEOUT_MS.
        private const val ENUM_WAIT_S = 180
        // One-time: apk download + setup-disk install over TCG can take a while.
        private const val PROVISION_TIMEOUT_MS = 720_000L
        // Persistent installed appliance (raw sparse image; usbip+ssh baked in).
        private const val APPLIANCE_DISK = "usb_vm_appliance.img"
        private const val APPLIANCE_DISK_SIZE = "2G"
        // Bump when APPLIANCE_EXTRA_PACKAGES changes — an already-provisioned
        // appliance with an older/missing version runs a cheap in-place
        // `apk add` upgrade boot instead of a full ISO re-provision. Markers
        // from before this scheme existed ("ok\n") parse as version 0.
        private const val APPLIANCE_PROVISION_VERSION = 3
        // cryptsetup = LUKS (unlockPartition). testdisk = partition-table +
        // deleted-file recovery (bundles photorec). gptfdisk = gdisk/sgdisk
        // (GPT repair). parted = MBR/GPT editing. smartmontools = smartctl
        // (drive health/forensic info). ddrescue = imaging a failing drive.
        // ntfs-3g/dosfstools = NTFS/FAT repair (fsck.ext4 already comes from
        // e2fsprogs above). All usable today via the terminal Haven already
        // gives you into the VM — this just makes them available without an
        // ad-hoc `apk add` every session. NOT yet verified on-device that every
        // one of these resolves cleanly from the pinned v3.21 repos.
        private const val APPLIANCE_EXTRA_PACKAGES =
            "cryptsetup testdisk gptfdisk parted smartmontools ddrescue ntfs-3g dosfstools"
        private const val SSH_TIMEOUT_MS = 30_000L
        private const val UNLOCK_TIMEOUT_MS = 30_000L
        // Bounded wait for the explicit sync+umount in closeDrive() — generous
        // enough to flush a real pending write, but an eject still has to end.
        private const val UNMOUNT_TIMEOUT_MS = 20_000L
        private const val PROGRESS_TICK_MS = 15_000L
        private const val APPLIANCE_NAME = "alpine-standard-3.21.7-x86_64.iso"
        private const val APPLIANCE_SIZE = 278_921_216L // fallback when no Content-Length
        private const val APPLIANCE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-standard-3.21.7-x86_64.iso"
        // Pinned point release; rotates when Alpine supersedes it (re-pin then).
        private const val APPLIANCE_SHA256 =
            "f1a3a93628927b382d31e7b173b12801342641f711d8c591b88582be1b29954a"
    }
}

/**
 * One QEMU Process's serial console — everything [QemuManager] previously
 * held as singleton fields (`vmProcess`/`serialReader`/`serial`), now one
 * instance per open drive (keyed by busid) plus one dedicated instance for
 * appliance provisioning, so multiple VMs can run concurrently (#287
 * multi-drive). Same wait/send/reap logic as before the refactor, just
 * scoped to `this` instead of the outer class.
 */
private class VmInstance {
    @Volatile var process: Process? = null
        private set
    @Volatile private var readerThread: Thread? = null
    private val serial = StringBuilder()
    private val serialLock = Any()

    val isAlive: Boolean get() = process?.isAlive == true

    /** Start [launch]([command]) and begin draining its serial. */
    fun start(command: String, launch: (String) -> Process) {
        synchronized(serialLock) { serial.setLength(0) }
        val proc = launch(command)
        process = proc
        readerThread = thread(name = "haven-qemu-serial", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().use { r ->
                    val chunk = CharArray(1024)
                    while (true) {
                        val n = r.read(chunk); if (n < 0) break
                        synchronized(serialLock) {
                            serial.append(chunk, 0, n)
                            if (serial.length > SERIAL_CAP_INTERNAL) serial.delete(0, serial.length - SERIAL_CAP_INTERNAL)
                        }
                    }
                }
            }
        }
    }

    fun send(s: String) {
        runCatching { process?.outputStream?.apply { write(s.toByteArray()); flush() } }
    }

    fun bufferSnapshot(): String = synchronized(serialLock) { serial.toString() }

    /**
     * [since] restricts parsing to bytes at/after that buffer offset — the
     * report is a fresh, complete re-scan of every partition each time it's
     * emitted (see [runtimeSetupScript]/[QemuManager.unlockPartition]'s
     * scripts), so scanning the whole VM-lifetime buffer would resurrect a
     * partition's now-stale earlier state (e.g. a partition unlocked since
     * the initial setup report would still show up as locked forever).
     */
    fun parseMounts(since: Int = 0): List<String> {
        val re = Regex("^HVNMOUNT:(/mnt/\\S+)$")
        return bufferSnapshot().drop(since).lineSequence()
            .map { it.trim().trimEnd('\r') }
            .mapNotNull { re.find(it)?.groupValues?.get(1) }
            .distinct().toList()
    }

    fun parseLocked(since: Int = 0): List<String> {
        val re = Regex("^HVNLOCKED:(\\S+)$")
        return bufferSnapshot().drop(since).lineSequence()
            .map { it.trim().trimEnd('\r') }
            .mapNotNull { re.find(it)?.groupValues?.get(1) }
            .distinct().toList()
    }

    fun awaitMarker(
        marker: String,
        timeoutMs: Long,
        nudge: Boolean = false,
        onProgress: ((elapsedSec: Long) -> Unit)? = null,
    ): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var nextTick = start + PROGRESS_TICK_MS_INTERNAL
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive) return false
            synchronized(serialLock) { if (serial.contains(marker)) return true }
            if (nudge) send("\n")
            val now = System.currentTimeMillis()
            if (onProgress != null && now >= nextTick) {
                onProgress((now - start) / 1000)
                nextTick = now + PROGRESS_TICK_MS_INTERNAL
            }
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return false }
        }
        return synchronized(serialLock) { serial.contains(marker) }
    }

    /**
     * Send [script] and wait for [markerRegex] (an alternation like `"A|B"`)
     * to appear in the serial buffer, returning which alternative matched (or
     * null on timeout/VM death). Only bytes written *after* this call's own
     * [send] count — a VM instance is long-lived (open, unlock, close all
     * share it), and a repeat command with the same marker text (e.g. a
     * second unlock attempt) would otherwise match a stale occurrence left
     * over from an earlier command instead of its own real output.
     */
    fun sendAndAwaitRegex(script: String, markerRegex: String, timeoutMs: Long): String? {
        val since = synchronized(serialLock) { serial.length }
        send(script + "\n")
        val re = Regex(markerRegex)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive) return null
            synchronized(serialLock) {
                re.find(serial, since.coerceAtMost(serial.length))?.let { return it.value }
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return null }
        }
        return synchronized(serialLock) { re.find(serial, since.coerceAtMost(serial.length))?.value }
    }

    /** Blocks up to [seconds] for the process to exit; true if it did (or there's none to wait for). */
    fun waitFor(seconds: Long): Boolean = runCatching { process?.waitFor(seconds, TimeUnit.SECONDS) != false }.getOrDefault(true)

    fun stop() {
        val proc = process ?: return
        process = null
        val launcher = pidOf(proc) ?: -1
        val tracees = if (launcher > 0) descendantPids(launcher) else emptyList()
        proc.destroy()
        if (runCatching { !proc.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(true)) proc.destroyForcibly()
        // proot's --kill-on-exit doesn't reap the ptrace tracee when the launcher
        // is force-destroyed; signal the snapshotted qemu tree directly.
        if (tracees.isNotEmpty()) {
            tracees.forEach { runCatching { android.os.Process.sendSignal(it, 15) } }
            runCatching { Thread.sleep(300) }
            tracees.forEach { runCatching { android.os.Process.killProcess(it) } }
            Log.d("QemuManager", "reaped ${tracees.size} qemu tracee(s): $tracees")
        }
        readerThread?.interrupt(); readerThread = null
    }
}

// Duplicated (not in QemuManager.Companion) so VmInstance — a top-level class,
// for clean multi-instance state — doesn't need an outer-class reference.
private const val SERIAL_CAP_INTERNAL = 256 * 1024
private const val PROGRESS_TICK_MS_INTERNAL = 15_000L

// tracee reaping — same approach as GuestServiceManager (proot ptrace).
private fun descendantPids(rootPid: Int): List<Int> {
    val childrenOf = mutableMapOf<Int, MutableList<Int>>()
    val procDirs = File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) } ?: return emptyList()
    for (dir in procDirs) {
        val pid = dir.name.toIntOrNull() ?: continue
        val ppid = readPpid(File(dir, "stat")) ?: continue
        childrenOf.getOrPut(ppid) { mutableListOf() }.add(pid)
    }
    val out = mutableListOf<Int>()
    val queue = ArrayDeque<Int>().apply { add(rootPid) }
    while (queue.isNotEmpty()) childrenOf[queue.removeFirst()]?.forEach { out.add(it); queue.add(it) }
    return out
}

private fun readPpid(statFile: File): Int? = try {
    val stat = statFile.readText()
    val after = stat.substringAfterLast(')').trim().split(" ")
    after.getOrNull(1)?.toIntOrNull()
} catch (_: Throwable) { null }

private fun pidOf(p: Process): Int? = try {
    val v = p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.get(p)
    when (v) { is Int -> v; is Long -> v.toInt(); else -> null }
} catch (_: Throwable) { null }

// How long (s) to wait INSIDE the VM for the drive to enumerate after usbip
// attach — a ceiling, not a fixed sleep (we mount the instant it appears).
private const val ENUM_WAIT_S = 180

/** Marker content is the provisioned package-set version; pre-LUKS markers ("ok\n") parse as 0 → mismatch → upgrade. */
internal fun markerVersion(marker: File): Int = marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0

/**
 * The single setup line typed at the appliance's root shell each open. The
 * appliance already has linux-tools-usbip + openssh (+ cryptsetup + the
 * recovery/forensics toolset) installed (provisioned once), so this only
 * does the per-open work: network up, authorise the caller's ephemeral key,
 * start sshd, attach the drive over USB/IP, mount every plain partition
 * ([readOnly] ? "ro" : "rw"). A LUKS-encrypted partition is left unmounted
 * and reported via HVNLOCKED — QemuManager.unlockPartition mounts it
 * afterwards, once the user supplies a passphrase. Top-level (not a
 * QemuManager member) — pure string-building, so it's unit-testable without
 * an Android Context.
 */
internal fun runtimeSetupScript(busid: String, pubKey: String, readOnly: Boolean): String {
    val mountCmd = if (readOnly) {
        // noload (skip journal replay) is only a valid option for ext4/xfs —
        // vfat/exfat/ntfs/ntfs3 reject the whole mount with "Unknown
        // parameter" if it's passed, wasting the one fallback attempt a
        // non-ext4/xfs stick gets. $t (blkid TYPE) is already set by the
        // caller's loop before this runs.
        "mount -o ro \"\$p\" \"\$d\" 2>/dev/null || " +
            "if [ \"\$t\" = ext4 ] || [ \"\$t\" = xfs ]; then mount -o ro,noload \"\$p\" \"\$d\" 2>/dev/null; fi"
    } else {
        // `sync` = every write flushes immediately, no write-back cache to
        // lose. Slower, but this is an emulated full-speed-limited USB link
        // already, and the alternative is a kill (VM OOM'd, app backgrounded,
        // battery pull) losing buffered writes and corrupting the filesystem —
        // the risk closeDrive()'s explicit unmount only covers for a *clean* eject.
        "mount -o rw,sync \"\$p\" \"\$d\" 2>/dev/null"
    }
    // pubKey is a single "ssh-ed25519 AAAA... comment" line (no single quotes).
    return "ip link set eth0 up; udhcpc -i eth0 -q -n; " +
        // Quiet the kernel console. The appliance boots with console=ttyS0,
        // so vhci_hcd/usb enumeration spam floods the serial we scrape — it
        // can trim the mount report out of the capped buffer before we read
        // it. Emergency-only from here (we're already past login).
        "dmesg -n 1 2>/dev/null; " +
        "mkdir -p /root/.ssh; echo '$pubKey' > /root/.ssh/authorized_keys; " +
        "chmod 700 /root/.ssh; chmod 600 /root/.ssh/authorized_keys; " +
        "ssh-keygen -A >/dev/null 2>&1; " +
        "sed -i 's/^#*PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config; " +
        "rc-service sshd restart >/dev/null 2>&1 || /usr/sbin/sshd; " +
        "modprobe vhci_hcd; " +
        // busybox `mount` auto-detect only works for filesystems already in
        // /proc/filesystems — it doesn't modprobe — so load the common ones
        // up front or an ext4 stick mounts as an empty dir.
        "for m in ext4 vfat exfat ntfs3; do modprobe \$m 2>/dev/null; done; " +
        // Attach, then WAIT FOR the block device to enumerate instead of
        // assuming a fixed time — a slower CPU or a bigger/slower drive can
        // take much longer than a fast phone. Poll until a partition node
        // appears, with a generous ceiling (still inside SETUP_TIMEOUT_MS,
        // and we proceed the instant it shows). usbip occasionally imports
        // at the wrong speed and never enumerates; a detach/re-attach usually
        // clears that but not always (seen it take 2+ tries live), so retry
        // every ~20s instead of once, using the same $ENUM_WAIT_S budget.
        "usbip attach -r 10.0.2.2 -b $busid 2>/dev/null; n=0; " +
        "while [ \$n -lt $ENUM_WAIT_S ]; do ls /dev/sd[a-z][0-9]* >/dev/null 2>&1 && break; " +
        "[ \$n -gt 0 ] && [ \$((\$n % 20)) -eq 0 ] && { usbip detach -p 00 2>/dev/null; usbip detach -p 0 2>/dev/null; sleep 1; " +
        "usbip attach -r 10.0.2.2 -b $busid 2>/dev/null; }; " +
        "n=\$((n+1)); sleep 1; done; " +
        "mkdir -p /mnt; for p in /dev/sd[a-z][0-9]*; do [ -b \"\$p\" ] || continue; " +
        "l=\$(basename \"\$p\"); d=/mnt/\$l; " +
        "t=\$(blkid -o value -s TYPE \"\$p\" 2>/dev/null); " +
        // LUKS partitions are reported (below) and left for unlockPartition —
        // mounting a raw LUKS block device would just fail anyway.
        "[ \"\$t\" = crypto_LUKS ] && continue; " +
        "mkdir -p \"\$d\"; $mountCmd; done; " +
        // Report locked LUKS partitions (not yet mapped) and the ACTUAL mounts
        // from /proc/mounts right before the marker, so both sit adjacent to
        // HAVEN_SETUP_DONE in the serial buffer (an inline echo during the
        // loop can be pushed out by console output before parseMounts/
        // parseLocked read). Authoritative, not best-effort.
        "for p in /dev/sd[a-z][0-9]*; do [ -b \"\$p\" ] || continue; l=\$(basename \"\$p\"); " +
        "t=\$(blkid -o value -s TYPE \"\$p\" 2>/dev/null); " +
        "[ \"\$t\" = crypto_LUKS ] && [ ! -e \"/dev/mapper/crypt_\$l\" ] && echo \"HVNLOCKED:\$l\"; done; " +
        "while read d m r; do case \"\$m\" in /mnt/*) echo \"HVNMOUNT:\$m\";; esac; done < /proc/mounts; " +
        "echo HAVEN_SETUP_DONE"
}
