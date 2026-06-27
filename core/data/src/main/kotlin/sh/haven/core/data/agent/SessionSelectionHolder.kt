package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A connect attempt that stalled on the interactive session-manager picker —
 * the profile uses tmux/zellij/screen and the remote already has existing
 * sessions, so [ConnectionsViewModel] is waiting for the user to choose one
 * (or "create new").
 *
 * @param sessionId the in-flight transport session the choice attaches to.
 * @param sessionNames existing remote session names to choose from.
 * @param previousSessionNames the subset open last time (the "restore" set).
 * @param suggestedNewName pre-filled name if "create new" is chosen.
 */
data class PendingSessionSelection(
    val profileId: String,
    val sessionId: String,
    val managerLabel: String,
    val sessionNames: List<String>,
    val previousSessionNames: List<String>,
    val suggestedNewName: String,
)

/**
 * App-singleton mirror of [ConnectionsViewModel]'s `_sessionSelection` picker
 * state so the MCP agent can observe it (`get_pending_session_picker`) and
 * answer it (`answer_session_picker`) without a human tap — closing the gap
 * where `connect_profile` to a session-manager profile with existing sessions
 * surfaced the picker and stalled, invisible and unanswerable over MCP.
 *
 * ConnectionsViewModel writes here whenever the picker appears or resolves;
 * McpTools reads the current value. Mirrors [PendingAuthPromptHolder].
 */
@Singleton
class SessionSelectionHolder @Inject constructor() {
    private val _selection = MutableStateFlow<PendingSessionSelection?>(null)
    val selection: StateFlow<PendingSessionSelection?> = _selection.asStateFlow()

    fun set(selection: PendingSessionSelection?) {
        _selection.value = selection
    }
}
