package sh.haven.core.local

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kill a proot-launched [Process] AND its ptrace tracees. proot's
 * `--kill-on-exit` does not reap the tracee when the launcher is
 * force-destroyed, so the snapshotted descendant PIDs are signalled
 * directly — the same approach [QemuManager]'s VmInstance uses for the USB
 * appliance VM (kept as a shared helper for the system-VM path, #326).
 */
internal fun killProotProcessTree(proc: Process, tag: String = "ProotProcessTree") {
    val launcher = pidOfProcess(proc) ?: -1
    val tracees = if (launcher > 0) descendantPidsOf(launcher) else emptyList()
    proc.destroy()
    if (runCatching { !proc.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(true)) proc.destroyForcibly()
    if (tracees.isNotEmpty()) {
        tracees.forEach { runCatching { android.os.Process.sendSignal(it, 15) } }
        runCatching { Thread.sleep(300) }
        tracees.forEach { runCatching { android.os.Process.killProcess(it) } }
        Log.d(tag, "reaped ${tracees.size} tracee(s): $tracees")
    }
}

/** BFS of /proc for every descendant PID of [rootPid]. */
internal fun descendantPidsOf(rootPid: Int): List<Int> {
    val childrenOf = mutableMapOf<Int, MutableList<Int>>()
    val procDirs = File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) } ?: return emptyList()
    for (dir in procDirs) {
        val pid = dir.name.toIntOrNull() ?: continue
        val ppid = readPpidOf(File(dir, "stat")) ?: continue
        childrenOf.getOrPut(ppid) { mutableListOf() }.add(pid)
    }
    val out = mutableListOf<Int>()
    val queue = ArrayDeque<Int>().apply { add(rootPid) }
    while (queue.isNotEmpty()) childrenOf[queue.removeFirst()]?.forEach { out.add(it); queue.add(it) }
    return out
}

private fun readPpidOf(statFile: File): Int? = try {
    // Field 4 (ppid) is the second token AFTER the ")" that closes comm —
    // parsing after the last ")" tolerates a comm containing spaces/parens.
    val after = statFile.readText().substringAfterLast(')').trim().split(" ")
    after.getOrNull(1)?.toIntOrNull()
} catch (_: Throwable) {
    null
}

private fun pidOfProcess(p: Process): Int? = try {
    p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.get(p) as? Int
} catch (_: Throwable) {
    null
}
