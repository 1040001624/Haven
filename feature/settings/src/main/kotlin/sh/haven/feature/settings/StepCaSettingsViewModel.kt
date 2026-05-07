package sh.haven.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.StepCaConfig
import sh.haven.core.data.repository.StepCaConfigRepository
import sh.haven.core.stepca.StepCaApiClient
import javax.inject.Inject

@HiltViewModel
class StepCaSettingsViewModel @Inject constructor(
    private val repository: StepCaConfigRepository,
    private val apiClient: StepCaApiClient,
) : ViewModel() {

    /** All registered CAs, newest first. */
    val configs: StateFlow<List<StepCaConfig>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Latest [StepCaApiClient.TestResult] keyed by CA id, for the row's status pill. */
    private val _testResults = MutableStateFlow<Map<String, StepCaApiClient.TestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, StepCaApiClient.TestResult>> = _testResults.asStateFlow()

    private val _testInFlight = MutableStateFlow<Set<String>>(emptySet())
    val testInFlight: StateFlow<Set<String>> = _testInFlight.asStateFlow()

    fun save(config: StepCaConfig) {
        viewModelScope.launch { repository.save(config) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            _testResults.value = _testResults.value - id
        }
    }

    fun test(config: StepCaConfig) {
        viewModelScope.launch {
            _testInFlight.value = _testInFlight.value + config.id
            try {
                val result = apiClient.testConnection(config)
                _testResults.value = _testResults.value + (config.id to result)
            } finally {
                _testInFlight.value = _testInFlight.value - config.id
            }
        }
    }

    /**
     * Auto-discover the SSH host CA public key from step-ca's
     * `/1.0/ssh/config` endpoint, using the dialog's current
     * caUrl + rootCertPem (a config that may not be saved yet).
     * Lets the user fill in the host-CA field with one tap rather
     * than pasting it manually. (#133 phase 2b)
     */
    suspend fun discoverSshHostCa(
        caUrl: String,
        rootCertPem: String,
    ): StepCaApiClient.SshConfigResult {
        val transient = StepCaConfig(
            name = "_discover",
            caUrl = caUrl,
            oidcIssuer = "",
            oidcAuthUrl = "",
            oidcTokenUrl = "",
            oidcClientId = "",
            provisioner = "",
            defaultPrincipals = "",
            rootCertPem = rootCertPem,
        )
        return apiClient.fetchSshConfig(transient)
    }
}
