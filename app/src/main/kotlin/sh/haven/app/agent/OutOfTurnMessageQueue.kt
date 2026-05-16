package sh.haven.app.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.haven.core.ssh.SshSessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Out-of-turn message queue (#161). The MCP `queue_self_message` tool
 * lets an agent inject text into the very Claude Code (or other REPL)
 * session that's driving the MCP traffic, by watching the SSH
 * terminal output for a prompt and typing the queued text when it
 * appears. The premise: the SSH session that carries the MCP reverse
 * tunnel *is* the session Claude Code is running in, so its agent-
 * scoped scrollback ring is a window into Claude Code's stdout. When
 * a prompt appears at the tail of the scrollback after the agent's
 * own turn has flushed, the message gets sent into the session as if
 * the user typed it — which is exactly the surface a slash command
 * like `/mcp reconnect haven` needs.
 *
 * Power-user feature: gated by [UserPreferencesRepository
 * .agentAllowQueueSelfMessage] in the dispatcher, on top of EVERY_CALL
 * consent in the tool itself. Both must be on.
 *
 * Polling-based rather than callback-based — simpler, and the tail of
 * the scrollback ring is cheap to read.
 */
@Singleton
class OutOfTurnMessageQueue @Inject constructor(
    private val sshSessionManager: SshSessionManager,
) {
    private data class QueuedMessage(
        val id: String,
        val sessionId: String,
        val text: String,
        val promptRegex: Regex,
        val deadline: Long,
        val baselineBytes: Int,
    )

    private val queue = ConcurrentHashMap<String, QueuedMessage>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { pollLoop() }
    }

    /**
     * Schedule [text] to be typed into [sessionId]'s SSH terminal when
     * the next output matching [promptPattern] (default: a line ending
     * in `> `) appears at the tail of the scrollback. Returns a queue
     * id the caller can use to [cancel] before delivery.
     *
     * The [baselineBytes] capture is the size of the agent scrollback
     * *at enqueue time* — the watcher only fires when new bytes have
     * been written since (so a prompt that was already on screen at
     * the moment the agent called this won't trigger an immediate
     * fire mid-turn; we wait for the agent's own turn output to flush
     * first).
     */
    fun enqueue(
        sessionId: String,
        text: String,
        promptPattern: String,
        timeoutSeconds: Int,
    ): String {
        val baseline = sshSessionManager.readAgentScrollback(sessionId, Int.MAX_VALUE)?.size ?: 0
        val id = UUID.randomUUID().toString()
        queue[id] = QueuedMessage(
            id = id,
            sessionId = sessionId,
            text = text,
            promptRegex = Regex(promptPattern, RegexOption.MULTILINE),
            deadline = System.currentTimeMillis() + timeoutSeconds.coerceAtLeast(1) * 1000L,
            baselineBytes = baseline,
        )
        return id
    }

    fun cancel(id: String): Boolean = queue.remove(id) != null

    fun pending(): List<String> = queue.keys.toList()

    private suspend fun pollLoop() {
        while (true) {
            delay(POLL_INTERVAL_MS)
            val now = System.currentTimeMillis()
            for ((id, msg) in queue.toMap()) {
                if (now > msg.deadline) {
                    queue.remove(id)
                    Log.d(TAG, "queue $id timed out without prompt match")
                    continue
                }
                val scrollback = sshSessionManager.readAgentScrollback(msg.sessionId, MAX_TAIL_BYTES)
                if (scrollback == null || scrollback.size <= msg.baselineBytes) continue
                val stripped = scrollback.decodeToString().let { stripAnsi(it) }
                // Look only at the last few lines — false positives from
                // earlier scrollback (`>` redirects in commands, code
                // snippets containing prompts) shouldn't ever fire.
                val tail = stripped.takeLast(TAIL_MATCH_CHARS)
                if (msg.promptRegex.containsMatchIn(tail)) {
                    if (queue.remove(id) != null) {
                        try {
                            sshSessionManager.sendInput(msg.sessionId, msg.text + "\n")
                            Log.d(TAG, "queue $id delivered to ${msg.sessionId}")
                        } catch (e: Exception) {
                            Log.w(TAG, "queue $id delivery failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun stripAnsi(s: String): String = ANSI_REGEX.replace(s, "")

    companion object {
        private const val TAG = "OutOfTurnQueue"
        private const val POLL_INTERVAL_MS = 200L
        private const val MAX_TAIL_BYTES = 4096
        private const val TAIL_MATCH_CHARS = 512
        private val ANSI_REGEX = Regex("\\[[0-9;?]*[a-zA-Z]")
    }
}
