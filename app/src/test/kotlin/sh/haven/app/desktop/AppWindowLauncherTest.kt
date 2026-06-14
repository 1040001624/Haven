package sh.haven.app.desktop

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.data.agent.AgentPresentationManager
import sh.haven.core.data.preferences.AppWindowDef
import sh.haven.core.data.preferences.AppWindowDefList
import sh.haven.core.data.preferences.AppWindowOrigin
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.LocalSessionManager

class AppWindowLauncherTest {

    private fun def(id: String = "d1") = AppWindowDef(
        id = id,
        label = "Calc",
        command = "galculator",
        createdBy = AppWindowOrigin.USER,
        fullscreen = true,
        resolution = null,
        scale = null,
        runAsRoot = false,
    )

    private fun session(state: DesktopManager.DesktopState, port: Int = 5901) =
        DesktopManager.AppWindowSession(
            sessionId = "appwin-1",
            command = "galculator",
            displayNumber = 1,
            vncPort = port,
            state = state,
        )

    private fun launcher(
        defs: List<AppWindowDef>,
        dm: DesktopManager,
        pm: AgentPresentationManager,
        repo: UserPreferencesRepository = mockk(relaxed = true),
    ): AppWindowLauncher {
        every { repo.appWindowDefs } returns flowOf(AppWindowDefList(defs))
        every { repo.appWindowDefaultResolution } returns flowOf("auto")
        every { repo.appWindowDefaultScale } returns flowOf(1f)
        coEvery { repo.upsertAppWindowDef(any(), any(), any(), any(), any(), any(), any()) } just Runs
        val lsm = mockk<LocalSessionManager>()
        every { lsm.desktopManager } returns dm
        return AppWindowLauncher(repo, lsm, pm)
    }

    @Test
    fun launchByIdDropsAMissingDef() = runBlocking {
        val dm = mockk<DesktopManager>()
        val pm = mockk<AgentPresentationManager>()
        val l = launcher(defs = emptyList(), dm = dm, pm = pm)

        assertNull(l.launchById("nope"))

        verify(exactly = 0) { dm.startAppWindow(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { pm.presentAppWindow(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun launchPresentsTheRunningCageWithTheDefsFullscreenAndCaption() = runBlocking {
        val dm = mockk<DesktopManager>()
        every { dm.startAppWindow(any(), any(), any(), runAsRoot = any()) } returns
            session(DesktopManager.DesktopState.RUNNING, port = 5907)
        val pm = mockk<AgentPresentationManager>(relaxed = true)
        val l = launcher(defs = listOf(def()), dm = dm, pm = pm)

        val message = l.launchById("d1")

        assertNull(message) // success → no user-facing message
        verify {
            pm.presentAppWindow(
                host = "127.0.0.1",
                port = 5907,
                sessionId = "appwin-1",
                caption = "Calc",
                fullscreen = true,
                scale = 1f,
                resolution = "auto",
            )
        }
    }
}
