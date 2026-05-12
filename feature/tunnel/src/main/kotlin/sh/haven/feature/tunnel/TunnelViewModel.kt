package sh.haven.feature.tunnel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.tunnel.CloudflareAccessTunnel
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Backs the "Tunnels" management screen. List/add/delete tunnel configs
 * (WireGuard for now; Tailscale wiring follows in a second pass once the
 * tsnet bridge is in place).
 */
@HiltViewModel
class TunnelViewModel @Inject constructor(
    private val repository: TunnelConfigRepository,
    private val connectionLogRepository: ConnectionLogRepository,
    private val httpClient: OkHttpClient,
) : ViewModel() {

    /**
     * Result of a [testCloudflareAccess] call, surfaced inline next to
     * the "Test connection" button.
     */
    sealed class CloudflareAccessTestResult {
        data object Idle : CloudflareAccessTestResult()
        data object Running : CloudflareAccessTestResult()
        data class Success(val message: String) : CloudflareAccessTestResult()
        data class Failure(val message: String) : CloudflareAccessTestResult()
    }

    private val _cfTestResult = MutableStateFlow<CloudflareAccessTestResult>(
        CloudflareAccessTestResult.Idle,
    )
    val cfTestResult: StateFlow<CloudflareAccessTestResult> = _cfTestResult.asStateFlow()

    fun resetCfTestResult() {
        _cfTestResult.value = CloudflareAccessTestResult.Idle
    }

    /**
     * Build a transient [CloudflareAccessTunnel] from the form's current
     * state and try a single dial+close against the Access gateway.
     *
     * Success means the WebSocket upgrade completed and the gateway
     * accepted the JWT. Failure carries the diagnostic detail produced
     * by `CloudflareAccessTunnel.mapFailure` — typically status code +
     * `cf-ray` + a body excerpt for Cloudflare error pages.
     *
     * Writes a row to the Connection Log either way so the user has a
     * persistent record after the inline UI clears. The
     * synthetic profileId `cf-access-test:<hostname>` keeps the row
     * out of the per-profile log lookups but visible in the global
     * summary view.
     */
    fun testCloudflareAccess(hostname: String, jwt: String) {
        val trimmedHost = hostname.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        if (trimmedHost.isEmpty() || jwt.isBlank()) {
            _cfTestResult.value = CloudflareAccessTestResult.Failure(
                "Need both a hostname and a captured JWT",
            )
            return
        }
        _cfTestResult.value = CloudflareAccessTestResult.Running
        viewModelScope.launch {
            val syntheticId = "cf-access-test:$trimmedHost"
            val started = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    val tunnel = CloudflareAccessTunnel.forTest(
                        hostname = trimmedHost,
                        jwt = jwt.trim(),
                        httpClient = httpClient.newBuilder()
                            .callTimeout(8, TimeUnit.SECONDS)
                            .build(),
                    )
                    try {
                        tunnel.dial(trimmedHost, 22, 8_000).close()
                    } finally {
                        tunnel.close()
                    }
                }
                val msg = "Gateway reachable; WebSocket upgrade succeeded."
                _cfTestResult.value = CloudflareAccessTestResult.Success(msg)
                connectionLogRepository.logEvent(
                    profileId = syntheticId,
                    status = ConnectionLog.Status.CONNECTED,
                    durationMs = System.currentTimeMillis() - started,
                    details = "Cloudflare Access test connection: $msg",
                )
            } catch (e: Throwable) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _cfTestResult.value = CloudflareAccessTestResult.Failure(msg)
                connectionLogRepository.logEvent(
                    profileId = syntheticId,
                    status = ConnectionLog.Status.FAILED,
                    durationMs = System.currentTimeMillis() - started,
                    details = "Cloudflare Access test connection failed: $msg",
                )
            }
        }
    }

    val tunnels: StateFlow<List<TunnelConfig>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun dismissError() {
        _error.value = null
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun dismissMessage() {
        _message.value = null
    }

    /**
     * Create a WireGuard tunnel config. Minimal validation — the Go-side
     * parser rejects malformed configs at connect time with a clear
     * message. Doing deep validation here would duplicate parser logic
     * and risk drifting out of sync.
     */
    fun addWireguardConfig(label: String, configText: String) {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (configText.isBlank()) {
            _error.value = "Config text is required"
            return
        }
        save(label, TunnelConfigType.WIREGUARD, configText.toByteArray())
    }

    /**
     * Create a Cloudflare Access SSH tunnel config (#154). [jwt] is the
     * `CF_Authorization` JWT obtained either from the in-app WebView
     * login flow or pasted from a desktop `cloudflared access token`
     * run. [expiresAtSeconds] is the JWT's `exp` claim — used by the
     * list view to flag stale rows; pass 0 if it couldn't be parsed
     * (we then skip expiry-aware UX for that row).
     *
     * The config is **per-hostname**: dial() on the resulting tunnel
     * only accepts the configured hostname. This is a deliberate
     * limitation of how `cloudflared access ssh` itself works.
     */
    fun addCloudflareAccessConfig(
        label: String,
        hostname: String,
        teamDomain: String,
        jwt: String,
        expiresAtSeconds: Long,
    ) {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (hostname.isBlank()) {
            _error.value = "Hostname is required"
            return
        }
        if (jwt.isBlank()) {
            _error.value = "Sign in to capture a Cloudflare Access JWT, or paste one in Advanced"
            return
        }
        val blob = sh.haven.core.tunnel.CloudflareAccessConfigBlob(
            hostname = hostname.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/'),
            teamDomain = teamDomain.trim().removePrefix("https://").removePrefix("http://"),
            jwt = jwt.trim(),
            jwtExpiresAt = expiresAtSeconds,
        )
        save(label, TunnelConfigType.CLOUDFLARE_ACCESS, blob.encode())
    }

    /**
     * Create a Tailscale tunnel config. The authkey joins the tailnet on
     * first use; tsnet persists node state under a per-config dir so
     * subsequent starts don't re-consume it.
     *
     * [controlURL] points at a self-hosted Headscale (or other compatible)
     * coordination server, e.g. "https://headscale.example.com". Leave
     * blank for the default Tailscale control plane (#124, mcbalaam).
     */
    fun addTailscaleConfig(label: String, authKey: String, controlURL: String = "") {
        if (label.isBlank()) {
            _error.value = "Label is required"
            return
        }
        if (authKey.isBlank()) {
            _error.value = "Auth key is required"
            return
        }
        val trimmedUrl = controlURL.trim()
        if (trimmedUrl.isNotEmpty() &&
            !trimmedUrl.startsWith("https://") &&
            !trimmedUrl.startsWith("http://")
        ) {
            _error.value = "Control plane URL must start with https:// (or http:// for local testing)"
            return
        }
        // Strip any leading/trailing whitespace paste artifacts — authkeys
        // are a single token with no internal spaces. Control URL likewise.
        val blob = sh.haven.core.tunnel.TailscaleConfigBlob(
            authKey = authKey.trim(),
            controlURL = trimmedUrl,
        )
        save(label, TunnelConfigType.TAILSCALE, blob.encode())
    }

    private fun save(label: String, type: TunnelConfigType, bytes: ByteArray) {
        viewModelScope.launch {
            try {
                repository.save(
                    TunnelConfig(
                        label = label.trim(),
                        type = type.name,
                        configText = bytes,
                    ),
                )
                _message.value = "Tunnel \"${label.trim()}\" saved"
            } catch (e: Exception) {
                _error.value = "Save failed: ${e.message}"
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.delete(id)
            } catch (e: Exception) {
                _error.value = "Delete failed: ${e.message}"
            }
        }
    }
}
