package sh.haven.feature.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailMessage
import sh.haven.core.mail.OutgoingMail
import javax.inject.Inject

/**
 * Drives the Mail screen: resolves the connected backend for the pending
 * profile, loads folders (auto-selecting the inbox), lists messages, parses a
 * message on open, and composes/sends (CP-7) over [MailBackend.sendMessage].
 */
@HiltViewModel
class MailViewModel @Inject constructor(
    private val transportSelector: MailTransportSelector,
) : ViewModel() {

    /** Which sub-view the screen shows, derived from [UiState]. */
    enum class View { FOLDERS, MESSAGES, READER, COMPOSE }

    data class UiState(
        val profileId: String? = null,
        val loading: Boolean = false,
        val folders: List<MailFolder> = emptyList(),
        val selectedFolder: MailFolder? = null,
        val messages: List<MailMessage> = emptyList(),
        val openMessage: ParsedMessage? = null,
        /** Non-null while the compose pane is open; overlays the pane underneath. */
        val compose: ComposeDraft? = null,
        /** True once a backend resolved, so compose/reply are available. */
        val canCompose: Boolean = false,
        /** Incremented after each successful send; the screen shows a one-shot snackbar on change. */
        val sentSignal: Int = 0,
        val error: String? = null,
    ) {
        val view: View get() = when {
            compose != null -> View.COMPOSE
            openMessage != null -> View.READER
            selectedFolder != null -> View.MESSAGES
            else -> View.FOLDERS
        }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var backend: MailBackend? = null

    /** Called by the screen with the profile to open; ignores repeats. */
    fun setPendingEmailProfile(profileId: String?) {
        if (profileId == null || profileId == _ui.value.profileId) return
        val resolved = transportSelector.resolve(profileId)
        if (resolved == null) {
            _ui.update { it.copy(profileId = profileId, error = "Not connected — open this account from Connections.") }
            return
        }
        backend = resolved
        _ui.update { UiState(profileId = profileId, loading = true, canCompose = true) }
        viewModelScope.launch { loadFolders() }
    }

    private suspend fun loadFolders() {
        val b = backend ?: return
        try {
            val folders = b.listFolders()
            val inbox = folders.firstOrNull { it.isInbox } ?: folders.firstOrNull()
            _ui.update { it.copy(loading = false, folders = folders, error = null) }
            inbox?.let { openFolder(it) }
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = e.message ?: "Failed to load folders") }
        }
    }

    fun openFolder(folder: MailFolder) {
        val b = backend ?: return
        _ui.update { it.copy(selectedFolder = folder, messages = emptyList(), openMessage = null, loading = true) }
        viewModelScope.launch {
            try {
                val msgs = b.listMessages(folder.id)
                _ui.update { it.copy(loading = false, messages = msgs, error = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "Failed to load messages") }
            }
        }
    }

    fun openMessage(message: MailMessage) {
        val b = backend ?: return
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val parsed = b.readMessage(message.id)
                _ui.update { it.copy(loading = false, openMessage = parsed, error = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "Failed to open message") }
            }
        }
    }

    /** Reader → message list. */
    fun closeMessage() = _ui.update { it.copy(openMessage = null) }

    /** Message list → folder list. */
    fun backToFolders() = _ui.update { it.copy(selectedFolder = null, messages = emptyList()) }

    fun clearError() = _ui.update { it.copy(error = null) }

    // ---- Compose / reply / forward (CP-7) -----------------------------------
    //
    // The compose pane overlays the current pane (View.COMPOSE wins in the
    // computed view), so discardCompose()/a successful send() simply clear
    // `compose` and the screen falls back to the reader or list underneath.

    /** Open a blank compose pane. */
    fun startCompose() {
        if (backend == null) return
        _ui.update { it.copy(compose = ComposeDraft()) }
    }

    /**
     * Open a reply to the currently-open message. [attributionLine] is the
     * locale-formatted "On <date>, <sender> wrote:" line built by the screen.
     * Note: we don't surface the account's own address into feature/mail, so a
     * reply-all may include the sender in Cc (honest limitation).
     */
    fun startReply(replyAll: Boolean, attributionLine: String) {
        val parsed = _ui.value.openMessage ?: return
        _ui.update {
            it.copy(compose = ComposeDrafting.buildReply(parsed, replyAll, attributionLine, selfAddress = null))
        }
    }

    /** Open a forward of the currently-open message. [forwardHeader] is the localized separator line. */
    fun startForward(forwardHeader: String) {
        val parsed = _ui.value.openMessage ?: return
        _ui.update { it.copy(compose = ComposeDrafting.buildForward(parsed, forwardHeader)) }
    }

    fun updateTo(v: String) = updateDraft { it.copy(to = v, sendError = null) }
    fun updateCc(v: String) = updateDraft { it.copy(cc = v) }
    fun updateBcc(v: String) = updateDraft { it.copy(bcc = v) }
    fun updateSubject(v: String) = updateDraft { it.copy(subject = v) }
    fun updateBody(v: String) = updateDraft { it.copy(body = v) }
    fun toggleCcBcc() = updateDraft { it.copy(showCcBcc = !it.showCcBcc) }

    private fun updateDraft(transform: (ComposeDraft) -> ComposeDraft) =
        _ui.update { state -> state.copy(compose = state.compose?.let(transform)) }

    /** Discard the compose pane, returning to the pane underneath. */
    fun discardCompose() = _ui.update { it.copy(compose = null) }

    /**
     * Send the current draft. On success the pane closes and [UiState.sentSignal]
     * advances (the screen shows a snackbar). On failure the pane stays open with
     * [ComposeDraft.sendError] so the user can retry. [recipientsRequiredMessage]
     * is the localized error used when the To field parses to no addresses.
     */
    fun send(recipientsRequiredMessage: String) {
        val b = backend ?: return
        val draft = _ui.value.compose ?: return
        if (draft.sending) return
        val toList = ComposeDrafting.parseRecipients(draft.to)
        if (toList.isEmpty()) {
            updateDraft { it.copy(sendError = recipientsRequiredMessage) }
            return
        }
        updateDraft { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                b.sendMessage(
                    OutgoingMail(
                        to = toList,
                        cc = ComposeDrafting.parseRecipients(draft.cc),
                        bcc = ComposeDrafting.parseRecipients(draft.bcc),
                        subject = draft.subject,
                        bodyText = draft.body,
                    ),
                )
                _ui.update { it.copy(compose = null, sentSignal = it.sentSignal + 1) }
            } catch (e: Exception) {
                updateDraft { it.copy(sending = false, sendError = e.message ?: "Send failed") }
            }
        }
    }
}
