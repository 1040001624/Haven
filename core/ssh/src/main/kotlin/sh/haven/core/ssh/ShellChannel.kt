package sh.haven.core.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

/**
 * A connected shell channel together with the streams JSch bound to it.
 *
 * The streams must be taken BEFORE `Channel.connect()`: `getInputStream()` is
 * what installs JSch's server→client pipe, and `Channel.write()` swallows the
 * NullPointerException when that pipe isn't there yet — so anything the server
 * sends before the first `getInputStream()` call is silently discarded. That is
 * what dropped the target's SSH banner through a jump host (#381); a shell
 * channel can lose its first output (banner, MOTD, prompt) the same way.
 *
 * Fetch them once and pass this around: a second `getInputStream()` installs a
 * FRESH pipe and orphans whatever the first one had already buffered, so
 * re-reading `channel.inputStream` downstream would reintroduce the bug.
 */
class ShellChannel(
    val channel: ChannelShell,
    val input: InputStream,
    val output: OutputStream,
) {
    val isConnected: Boolean get() = channel.isConnected
    val isClosed: Boolean get() = channel.isClosed
    val exitStatus: Int get() = channel.exitStatus

    fun disconnect() = channel.disconnect()
}

/**
 * Open an interactive shell channel on [session], binding its streams before
 * connecting it. See [ShellChannel] for why the order matters.
 */
internal fun openShellOn(
    session: Session,
    term: String,
    cols: Int,
    rows: Int,
    agentForwarding: Boolean,
): ShellChannel {
    val channel = session.openChannel("shell") as ChannelShell
    channel.setPtyType(term, cols, rows, 0, 0)
    if (agentForwarding) channel.setAgentForwarding(true)
    val input = channel.inputStream
    val output = channel.outputStream
    channel.connect()
    return ShellChannel(channel, input, output)
}
