package sh.haven.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.GuestServiceManager
import sh.haven.core.local.LocalSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #409 — optional idle auto-stop for the local PRoot Linux guest.
 *
 * [onBackground] is called when the app leaves the foreground (from
 * MainActivity.onPause). If the user set a non-zero idle timeout, a delayed job
 * stops the interactive guest — local terminal sessions + desktops — after that
 * long. [onForeground] (MainActivity.onResume) cancels the pending stop, so the
 * many brief pauses (a permission dialog, the recents peek) never reap: only a
 * genuine walk-away that outlasts the timeout does.
 *
 * A **running guest service** suppresses the reap entirely — starting a service
 * is an explicit "keep PRoot up" choice, so idle reclamation must not fight it.
 *
 * Off by default (timeout 0), and opt-in by nature: it will also stop a
 * long-running job in a backgrounded terminal, so the point is reclaiming a
 * guest the user walked away from, not preserving background work.
 */
@Singleton
class ProotIdleReaper @Inject constructor(
    private val localSessionManager: LocalSessionManager,
    private val guestServiceManager: GuestServiceManager,
    private val preferencesRepository: UserPreferencesRepository,
) {
    /** Test seam: the scope the delayed reap runs on (swap for a TestScope). */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    /** App came to the foreground — cancel any pending idle stop. */
    fun onForeground() {
        pending?.cancel()
        pending = null
    }

    /** App went to the background — arm the idle stop if a timeout is set. */
    fun onBackground() {
        pending?.cancel()
        pending = scope.launch {
            val minutes = preferencesRepository.prootIdleTimeoutMinutes.first()
            if (minutes <= 0) return@launch
            delay(minutes * 60_000L)
            reap()
        }
    }

    /** Stop the interactive guest, unless a guest service wants PRoot kept up. */
    internal fun reap() {
        val serviceRunning = guestServiceManager.services.value.values.any {
            it.state == GuestServiceManager.ServiceState.RUNNING
        }
        if (serviceRunning) return
        // Both are idempotent no-ops when nothing is running.
        localSessionManager.disconnectAll()
        localSessionManager.desktopManager.stopAll()
    }
}
