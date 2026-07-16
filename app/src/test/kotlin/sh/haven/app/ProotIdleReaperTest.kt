package sh.haven.app

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.GuestServiceManager
import sh.haven.core.local.LocalSessionManager

@OptIn(ExperimentalCoroutinesApi::class)
class ProotIdleReaperTest {

    private val local = mockk<LocalSessionManager>(relaxed = true)
    private val services = mockk<GuestServiceManager>(relaxed = true)
    private val prefs = mockk<UserPreferencesRepository>(relaxed = true)

    private fun reaper(timeoutMinutes: Int, serviceRunning: Boolean = false): ProotIdleReaper {
        every { prefs.prootIdleTimeoutMinutes } returns flowOf(timeoutMinutes)
        val svcMap = if (serviceRunning) {
            mapOf("svc" to mockk<GuestServiceManager.GuestServiceInstance>(relaxed = true) {
                every { state } returns GuestServiceManager.ServiceState.RUNNING
            })
        } else {
            emptyMap()
        }
        every { services.services } returns MutableStateFlow(svcMap)
        return ProotIdleReaper(local, services, prefs)
    }

    @Test
    fun `reap stops the interactive guest when no service runs`() {
        reaper(15).reap()
        verify { local.disconnectAll() }
        verify { local.desktopManager.stopAll() }
    }

    @Test
    fun `reap is suppressed while a guest service is running`() {
        reaper(15, serviceRunning = true).reap()
        verify(exactly = 0) { local.disconnectAll() }
        verify(exactly = 0) { local.desktopManager.stopAll() }
    }

    @Test
    fun `background then idle past the timeout stops the guest`() = runTest {
        val r = reaper(15).apply { scope = this@runTest }
        r.onBackground()
        advanceUntilIdle() // let the 15-min delay elapse on virtual time
        verify { local.disconnectAll() }
    }

    @Test
    fun `returning to the foreground before the timeout cancels the stop`() = runTest {
        val r = reaper(15).apply { scope = this@runTest }
        r.onBackground()
        advanceTimeBy(5 * 60_000L)
        r.onForeground()
        advanceUntilIdle()
        verify(exactly = 0) { local.disconnectAll() }
    }

    @Test
    fun `a zero timeout never stops the guest`() = runTest {
        val r = reaper(0).apply { scope = this@runTest }
        r.onBackground()
        advanceUntilIdle()
        verify(exactly = 0) { local.disconnectAll() }
    }
}
