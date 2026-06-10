package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App→feature bridge for "which connection is the MCP agent operating on right now".
 *
 * The `app`-layer MCP dispatch ([sh.haven.app.agent] McpTools) calls [touch] with the
 * profile a tool targets; the Connections UI observes [activeProfiles] and lights that
 * connection's per-row MCP indicator for a short window. Lives in `core/data` (like
 * `AgentUiCommandBus`) so both modules share it without an app→feature dependency.
 *
 * Holds only a `profileId → lastActivityAtMs` map — the "is it active now?" window is
 * applied in the UI against a ticking clock, so no timers live here.
 */
@Singleton
class AgentActivityHolder @Inject constructor() {

    private val _activeProfiles = MutableStateFlow<Map<String, Long>>(emptyMap())
    val activeProfiles: StateFlow<Map<String, Long>> = _activeProfiles.asStateFlow()

    /** Record that the agent just operated on [profileId]. No-op for a blank id. */
    fun touch(profileId: String, atMs: Long = System.currentTimeMillis()) {
        if (profileId.isBlank()) return
        _activeProfiles.update { it + (profileId to atMs) }
    }
}
