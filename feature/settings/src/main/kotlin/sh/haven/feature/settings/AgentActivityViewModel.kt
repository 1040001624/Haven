package sh.haven.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.haven.core.data.db.AgentAuditEventDao
import sh.haven.core.data.db.entities.AgentAuditEvent
import sh.haven.core.data.db.entities.StandingPolicy
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.StandingPolicyRepository
import javax.inject.Inject

/**
 * Backs [AgentActivityScreen]. The screen reads directly from the
 * audit DAO — there's no repository layer here because the surface is
 * pure read + clear, and adding a repo would only forward calls.
 *
 * On open, the ViewModel marks the current latest timestamp as "seen"
 * so the Settings badge clears. The badge logic itself lives in
 * [SettingsViewModel] (it needs the same flow + the prefs key) but
 * the marking always happens here, where the user actually looks at
 * the events.
 */
@HiltViewModel
class AgentActivityViewModel @Inject constructor(
    private val dao: AgentAuditEventDao,
    private val preferences: UserPreferencesRepository,
    private val standingPolicyRepository: StandingPolicyRepository,
) : ViewModel() {

    val events: StateFlow<List<AgentAuditEvent>> =
        dao.observeRecent(limit = 200)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Tier-3 standing policies — surfaced here because this screen is the
     * kill-switch the consent model promises (VISION.md "Consent tiers"):
     * the place a user sees every live no-prompt grant and revokes it.
     */
    val standingPolicies: StateFlow<List<StandingPolicy>> =
        standingPolicyRepository.observePolicies()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun revokePolicy(id: String) {
        viewModelScope.launch { standingPolicyRepository.deletePolicy(id) }
    }

    init {
        markAllSeen()
    }

    /**
     * Persist "everything currently in the table is seen." Called once
     * on screen open and again whenever the user clears the log, so a
     * stale unseen-count from before the clear can't linger.
     */
    fun markAllSeen() {
        viewModelScope.launch {
            val latest = dao.latestTimestamp() ?: System.currentTimeMillis()
            preferences.setLastViewedAgentAuditTimestamp(latest)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            dao.deleteAll()
            // After a wipe there's nothing to show as new; record the
            // current time so any in-flight insert that races us
            // doesn't immediately re-light the badge.
            preferences.setLastViewedAgentAuditTimestamp(System.currentTimeMillis())
        }
    }
}
