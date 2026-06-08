package sh.haven.feature.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.haven.core.mail.MailEngine
import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailMessage
import sh.haven.core.mail.MailSessionManager
import sh.haven.core.mail.OutgoingMail
import javax.inject.Inject

/**
 * Drives the Mail screen. Multi-account aware: observes every connected mail
 * session, tracks an *active* account (whose folders/messages it shows), and
 * lets the user switch accounts. Compose names the sending account ("From") and
 * can target any connected account, so it's always clear whether a send goes via
 * Proton, an IMAP/Gmail account, etc.
 */
@HiltViewModel
class MailViewModel @Inject constructor(
    private val transportSelector: MailTransportSelector,
    private val mailSessionManager: MailSessionManager,
) : ViewModel() {

    /** Which sub-view the screen shows, derived from [UiState]. */
    enum class View { FOLDERS, MESSAGES, READER, COMPOSE }

    /** A connected mail account the user can view or send from. */
    data class MailAccount(
        val profileId: String,
        val label: String,
        val engine: MailEngine,
    )

    data class UiState(
        /** Every currently-connected mail account. */
        val accounts: List<MailAccount> = emptyList(),
        /** The account whose folders/messages are shown, and the default "From". */
        val activeProfileId: String? = null,
        val loading: Boolean = false,
        val folders: List<MailFolder> = emptyList(),
        val selectedFolder: MailFolder? = null,
        val messages: List<MailMessage> = emptyList(),
        val openMessage: ParsedMessage? = null,
        /** Non-null while the compose pane is open; overlays the pane underneath. */
        val compose: ComposeDraft? = null,
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

        /** The active account, or null when nothing is connected. */
        val activeAccount: MailAccount? get() = accounts.firstOrNull { it.profileId == activeProfileId }

        /** Compose/reply are available once an account is active. */
        val canCompose: Boolean get() = activeProfileId != null

        fun accountFor(profileId: String?): MailAccount? =
            profileId?.let { id -> accounts.firstOrNull { it.profileId == id } }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** The account navigated to (via connect); preferred when picking the active account. */
    private var pendingProfileId: String? = null

    /** The account whose folders are currently loaded, to avoid redundant reloads. */
    private var loadedProfileId: String? = null

    init {
        viewModelScope.launch {
            mailSessionManager.sessions.collect { onSessionsChanged(it) }
        }
    }

    /** Keep [UiState.accounts] in sync with the live sessions and keep an account active. */
    private fun onSessionsChanged(sessions: Map<String, MailSessionManager.SessionState>) {
        val connected = sessions.values
            .filter { it.status == MailSessionManager.SessionState.Status.CONNECTED }
            .map { MailAccount(it.profileId, it.label, it.engine) }
            .distinctBy { it.profileId }

        val current = _ui.value
        // Keep the current active account if it's still connected; otherwise prefer
        // the pending (just-navigated) account, then fall back to the first connected.
        val active = current.activeProfileId?.takeIf { id -> connected.any { it.profileId == id } }
            ?: pendingProfileId?.takeIf { id -> connected.any { it.profileId == id } }
            ?: connected.firstOrNull()?.profileId

        _ui.update { it.copy(accounts = connected, activeProfileId = active) }

        when {
            active == null -> {
                // Nothing connected: clear the view.
                loadedProfileId = null
                _ui.update {
                    it.copy(folders = emptyList(), selectedFolder = null, messages = emptyList(), openMessage = null)
                }
            }
            active != loadedProfileId -> activate(active)
        }
    }

    /** Called by the screen with the profile to open (from the connect navigation). */
    fun setPendingEmailProfile(profileId: String?) {
        if (profileId == null) return
        pendingProfileId = profileId
        if (mailSessionManager.isProfileConnected(profileId) && _ui.value.activeProfileId != profileId) {
            activate(profileId)
        }
    }

    /** Switch the viewed account (from the header switcher). */
    fun selectAccount(profileId: String) {
        if (profileId != _ui.value.activeProfileId) activate(profileId)
    }

    /** Make [profileId] the active account and (re)load its folders. */
    private fun activate(profileId: String) {
        loadedProfileId = profileId
        _ui.update {
            it.copy(
                activeProfileId = profileId,
                loading = true,
                folders = emptyList(),
                selectedFolder = null,
                messages = emptyList(),
                openMessage = null,
                compose = null,
                error = null,
            )
        }
        viewModelScope.launch { loadFolders(profileId) }
    }

    private fun backendFor(profileId: String?): MailBackend? =
        profileId?.let { transportSelector.resolve(it) }

    private suspend fun loadFolders(profileId: String) {
        val b = backendFor(profileId) ?: run {
            _ui.update { it.copy(loading = false, error = "Not connected — open this account from Connections.") }
            return
        }
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
        val b = backendFor(_ui.value.activeProfileId) ?: return
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
        val b = backendFor(_ui.value.activeProfileId) ?: return
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
    // Every draft carries the account it sends from (fromProfileId), defaulting
    // to the active account and overridable in the From row.

    /** Open a blank compose pane from the active account. */
    fun startCompose() {
        val active = _ui.value.activeProfileId ?: return
        _ui.update { it.copy(compose = ComposeDraft(fromProfileId = active)) }
    }

    /**
     * Open a reply to the currently-open message, sending from the active account.
     * [attributionLine] is the locale-formatted "On <date>, <sender> wrote:" line
     * built by the screen. Note: we don't surface the account's own address into
     * feature/mail, so a reply-all may include the sender in Cc (honest limitation).
     */
    fun startReply(replyAll: Boolean, attributionLine: String) {
        val parsed = _ui.value.openMessage ?: return
        val active = _ui.value.activeProfileId ?: return
        _ui.update {
            it.copy(
                compose = ComposeDrafting.buildReply(parsed, replyAll, attributionLine, selfAddress = null)
                    .copy(fromProfileId = active),
            )
        }
    }

    /** Open a forward of the currently-open message from the active account. */
    fun startForward(forwardHeader: String) {
        val parsed = _ui.value.openMessage ?: return
        val active = _ui.value.activeProfileId ?: return
        _ui.update {
            it.copy(compose = ComposeDrafting.buildForward(parsed, forwardHeader).copy(fromProfileId = active))
        }
    }

    /** Change the account a draft sends from (the compose "From" picker). */
    fun setComposeFrom(profileId: String) = updateDraft { it.copy(fromProfileId = profileId) }

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
     * Send the current draft via its [ComposeDraft.fromProfileId] account. On
     * success the pane closes and [UiState.sentSignal] advances (the screen shows
     * a snackbar). On failure the pane stays open with [ComposeDraft.sendError] so
     * the user can retry. [recipientsRequiredMessage] is the localized error used
     * when the To field parses to no addresses.
     */
    fun send(recipientsRequiredMessage: String) {
        val draft = _ui.value.compose ?: return
        if (draft.sending) return
        val b = backendFor(draft.fromProfileId) ?: run {
            updateDraft { it.copy(sendError = "Not connected — pick a connected account to send from.") }
            return
        }
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
