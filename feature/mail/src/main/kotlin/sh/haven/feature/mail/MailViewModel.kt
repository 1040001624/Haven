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
import javax.inject.Inject

/**
 * Drives the Mail screen: resolves the connected backend for the pending
 * profile, loads folders (auto-selecting the inbox), lists messages, and parses
 * a message on open. Read-only in v1 — no compose/send.
 */
@HiltViewModel
class MailViewModel @Inject constructor(
    private val transportSelector: MailTransportSelector,
) : ViewModel() {

    /** Which sub-view the screen shows, derived from [UiState]. */
    enum class View { FOLDERS, MESSAGES, READER }

    data class UiState(
        val profileId: String? = null,
        val loading: Boolean = false,
        val folders: List<MailFolder> = emptyList(),
        val selectedFolder: MailFolder? = null,
        val messages: List<MailMessage> = emptyList(),
        val openMessage: ParsedMessage? = null,
        val error: String? = null,
    ) {
        val view: View get() = when {
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
        _ui.update { UiState(profileId = profileId, loading = true) }
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
}
