package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.haven.core.local.proot.PackageFamily
import sh.haven.core.local.proot.PackageOps
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Boots a full **system** QEMU VM (real kernel, arbitrary arch) inside the
 * active proot and exposes its display over **VNC on loopback**, so Haven's
 * existing VNC viewer can render and drive it (#326). Distinct from
 * [QemuManager] (the USB-drive appliance VM, #287) and from qemu-**user**
 * (#325, per-binary translation, no VM).
 *
 * The chain is proven end-to-end on-device (Phase 0 spike): a native-arm64
 * proot runs `qemu-system-x86_64` under TCG (no `/dev/kvm` on unrooted
 * Android — slow but correct), `-vnc 127.0.0.1:N -vga std` binds a VNC
 * server on loopback, and a Haven VNC connection to `127.0.0.1:(5900+N)`
 * renders the guest with working keyboard input.
 *
 * ### Caveats baked into the design
 * - **One VM at a time** — TCG plus phone RAM make concurrent system VMs
 *   impractical; [start] refuses while one is running.
 * - **Needs a VNC-capable qemu.** Alpine's qemu is a stripped build with no
 *   VNC; Debian's has it. We don't try to detect that statically — the VNC
 *   port simply never binds, which [start] surfaces as a clear error.
 * - The disk is a path **inside the active proot** (e.g. under `/tmp`, which
 *   is the app cacheDir). Image provisioning is a later phase; this manager
 *   only owns the VM lifecycle.
 */
@Singleton
class SystemVmManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
) {
    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    data class VmState(
        val diskPath: String,
        val status: Status,
        /** Loopback VNC port to point a Haven VNC connection at (127.0.0.1:vncPort). Null unless RUNNING. */
        val vncPort: Int? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow<VmState?>(null)
    val state: StateFlow<VmState?> = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Boot [diskGuestPath] (a path inside the active proot) as an x86_64
     * system VM with a VNC display on a free loopback port. Returns the
     * running [VmState] whose [VmState.vncPort] the caller wires into a Haven
     * VNC connection; throws [SystemVmException] on any failure (and leaves
     * state ERROR). One VM at a time — call [stop] first to replace.
     */
    suspend fun start(
        diskGuestPath: String,
        memMb: Int = DEFAULT_MEM_MB,
        cpus: Int = DEFAULT_CPUS,
    ): VmState = mutex.withLock {
        if (process?.isAlive == true) {
            throw SystemVmException("A system VM is already running — stop it first.")
        }
        ensureQemuInstalled()

        // qemu `-vnc host:D` binds port 5900+D. Grab a free loopback port and
        // derive the display from it; ephemeral ports are well above 5900, so
        // the subtraction is always valid.
        val port = freeLoopbackPort()
        require(port >= VNC_BASE_PORT) { "no usable VNC port (got $port)" }
        val display = port - VNC_BASE_PORT

        _state.value = VmState(diskGuestPath, Status.STARTING, vncPort = port)
        val command = qemuVncCommand(diskGuestPath, display, memMb, cpus)
        Log.i(TAG, "starting system VM: $command")

        val proc = withContext(Dispatchers.IO) { prootManager.startCommandInProot(command) }
        process = proc

        // qemu binds the VNC server at startup (before the guest boots), so a
        // successful loopback connect means the display is up. A timeout here
        // is the empirical "this qemu build has no VNC" signal (e.g. Alpine).
        val ready = withContext(Dispatchers.IO) { awaitPortOpen(port, VNC_BIND_TIMEOUT_MS) }
        if (!ready || proc.isAlive.not()) {
            val msg = when {
                !proc.isAlive -> "qemu exited before the VNC server came up (check the disk path / qemu args)"
                else -> "VNC server never bound on 127.0.0.1:$port — this distro's qemu likely has no VNC support (Alpine's does not; try Debian)"
            }
            stopLocked()
            _state.value = VmState(diskGuestPath, Status.ERROR, error = msg)
            throw SystemVmException(msg)
        }

        VmState(diskGuestPath, Status.RUNNING, vncPort = port).also { _state.value = it }
    }

    /** Power off / kill the running VM (idempotent). */
    suspend fun stop() = mutex.withLock { stopLocked() }

    private fun stopLocked() {
        process?.let { killProotProcessTree(it, TAG) }
        process = null
        _state.value = _state.value?.copy(status = Status.STOPPED, vncPort = null)
    }

    /**
     * Ensure `qemu-system-x86_64` is present in the active distro, installing
     * it via that distro's package manager if not (mirrors
     * [QemuManager.ensureQemu]). VNC *capability* is verified empirically at
     * boot (the port-bind wait), not here.
     */
    private suspend fun ensureQemuInstalled() {
        val (found, _) = prootManager.runCommandInProot("command -v $QEMU_BIN || true")
        if (found.contains(QEMU_BIN)) return
        val family = prootManager.activeDistro.family
        val pkg = qemuPackageFor(family)
        val ops = PackageOps.forFamily(family)
        Log.i(TAG, "installing $pkg in ${prootManager.activeDistroId}")
        val (out, code) = prootManager.runCommandInProot(
            "${ops.updateCmd()} >/dev/null 2>&1 ; ${ops.installCmd(listOf(pkg))} 2>&1",
        )
        val (recheck, _) = prootManager.runCommandInProot("command -v $QEMU_BIN || true")
        if (!recheck.contains(QEMU_BIN)) {
            throw SystemVmException(
                "Could not install $pkg in ${prootManager.activeDistroId} (exit $code): ${out.takeLast(200)}",
            )
        }
    }

    /** Poll a loopback TCP connect until [port] accepts or [timeoutMs] elapses (or qemu dies). */
    private fun awaitPortOpen(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive == false) return false
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                true
            }.getOrDefault(false)
            if (ok) return true
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    companion object {
        private const val TAG = "SystemVmManager"
        private const val QEMU_BIN = "qemu-system-x86_64"
        const val VNC_BASE_PORT = 5900
        const val DEFAULT_MEM_MB = 2048
        const val DEFAULT_CPUS = 2
        private const val VNC_BIND_TIMEOUT_MS = 20_000L
    }
}

class SystemVmException(message: String) : Exception(message)

/**
 * Pure builder for the runtime qemu command — the VNC system-VM analogue of
 * [QemuManager]'s `qemuRuntimeCommand`. `exec` so the launcher process *is*
 * qemu (clean to signal); `-vnc 127.0.0.1:[display]` serves the VGA over VNC
 * on port `5900+display`; `-boot c` boots the installed disk; user-net gives
 * the guest outbound networking. Extracted top-level so it unit-tests without
 * an Android Context.
 */
internal fun qemuVncCommand(diskGuestPath: String, display: Int, memMb: Int, cpus: Int): String =
    "exec qemu-system-x86_64 -M pc -m $memMb -smp $cpus -monitor none " +
        "-drive file=$diskGuestPath,if=virtio,format=raw " +
        "-vga std -vnc 127.0.0.1:$display " +
        "-netdev user,id=n0 -device virtio-net-pci,netdev=n0 " +
        "-boot c -no-reboot"

/** The qemu-system package name for a distro family (Debian's is VNC-capable; Alpine's is not). */
internal fun qemuPackageFor(family: PackageFamily): String = when (family) {
    PackageFamily.APK -> "qemu-system-x86_64"
    PackageFamily.APT -> "qemu-system-x86"
    PackageFamily.PACMAN -> "qemu-system-x86"
    PackageFamily.XBPS -> "qemu"
    else -> "qemu"
}

/** A free loopback TCP port (bound momentarily, then released for qemu to claim). */
private fun freeLoopbackPort(): Int =
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
