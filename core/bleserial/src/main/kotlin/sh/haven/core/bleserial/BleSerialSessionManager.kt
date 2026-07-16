package sh.haven.core.bleserial

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BleSerialSessionManager"

/**
 * Manages BLE-serial terminal sessions, mirroring [sh.haven.core.btserial.BtSerialSessionManager]:
 * register → connect (open the GATT link) → create terminal → detach/reattach/remove.
 *
 * The GATT connect is the only blocking part and runs on IO in [connectSession];
 * everything else is registry bookkeeping.
 */
@Singleton
class BleSerialSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val deviceAddress: String = "",
        val params: BleSerialParams = BleSerialParams(),
        val deviceName: String? = null,
        /** Open GATT link, set by [connectSession], consumed by [createTerminalSession]. */
        val link: BleSerialLink? = null,
        val session: BleSerialSession? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val connector by lazy { AndroidBleSerialConnector(context) }

    /**
     * Opens the GATT link. Test seam — the real path connects via
     * [AndroidBleSerialConnector]; tests substitute a fake link.
     */
    internal var openLink: (address: String, params: BleSerialParams) -> BleSerialLink =
        { address, params -> connector.connect(address, params) }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bleserial-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED || it.status == SessionState.Status.CONNECTING
        }

    /** Register a new session (status CONNECTING). Returns the generated sessionId. */
    fun registerSession(
        profileId: String,
        label: String,
        deviceAddress: String,
        params: BleSerialParams = BleSerialParams(),
    ): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                deviceAddress = deviceAddress,
                params = params,
            ))
        }
        return sessionId
    }

    /**
     * Open the GATT link (blocking, on IO). Sets status CONNECTED and stashes the
     * link for [createTerminalSession]. Throws on failure (status → ERROR).
     */
    suspend fun connectSession(sessionId: String) {
        val state = _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        if (state.link != null || state.session != null) return
        if (state.deviceAddress.isEmpty()) throw IllegalStateException("No device address for $sessionId")

        val link = try {
            withContext(Dispatchers.IO) { openLink(state.deviceAddress, state.params) }
        } catch (e: Exception) {
            Log.e(TAG, "GATT connect failed for ${state.label}: ${e.message}")
            updateStatus(sessionId, SessionState.Status.ERROR)
            throw e
        }
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                deviceName = link.displayName,
                link = link,
            ))
        }
    }

    /**
     * Wrap the already-open link ([connectSession] first) in a terminal session,
     * wiring peripheral output to [onDataReceived]. Non-suspend so the terminal
     * syncSessions loop can call it. Idempotent.
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): BleSerialSession? {
        val state = _sessions.value[sessionId] ?: return null
        state.session?.let { return it }
        val link = state.link ?: return null

        val session = BleSerialSession(
            sessionId = sessionId,
            link = link,
            onDataReceived = onDataReceived,
            onDisconnected = { updateStatus(it, SessionState.Status.DISCONNECTED) },
        )
        session.start()

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(session = session))
        }
        return session
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val s = _sessions.value[sessionId] ?: return false
        return s.status == SessionState.Status.CONNECTED && s.session == null && s.link != null
    }

    /** Detach the terminal without dropping the link. */
    fun detachTerminalSession(sessionId: String) {
        val state = _sessions.value[sessionId] ?: return
        state.session?.detach()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(session = null))
        }
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    /** Send [text] as UTF-8 to the session (sendInput contract, #366). */
    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]?.session
            ?: throw IllegalStateException("No live BLE-serial session: $sessionId")
        session.sendInput(text.toByteArray(Charsets.UTF_8))
    }

    // Close the terminal session if one exists (it owns the link), else the bare
    // link opened by connectSession before a terminal was created.
    private fun tearDown(state: SessionState) {
        runCatching { state.session?.close() ?: state.link?.close() }
            .onFailure { Log.e(TAG, "tearDown failed for ${state.sessionId}", it) }
    }

    fun removeSession(sessionId: String) {
        val state = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute { tearDown(state) }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute { toRemove.forEach { tearDown(it) } }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute { snapshot.forEach { tearDown(it) } }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }
}
