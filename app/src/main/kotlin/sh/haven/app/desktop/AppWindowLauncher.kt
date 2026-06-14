package sh.haven.app.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sh.haven.core.data.agent.AgentPresentationManager
import sh.haven.core.data.preferences.AppWindowDef
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.LocalSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts a saved app window (cage kiosk) and queues it into the
 * presentation overlay, with no composed UI required. Extracted from
 * [DesktopViewModel.launchAppWindow] so a home-screen launcher shortcut can
 * reach it on a cold start: the presentation goes straight onto
 * [AgentPresentationManager]'s retained queue — the `AgentUiCommandBus` is
 * `replay = 0` and would drop an emit fired before any collector mounts,
 * whereas the presentation queue is rendered whenever `PresentationHost`
 * composes.
 */
@Singleton
class AppWindowLauncher @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    localSessionManager: LocalSessionManager,
    private val presentationManager: AgentPresentationManager,
) {
    private val desktopManager: DesktopManager = localSessionManager.desktopManager

    /** Resolve [defId] from prefs and [launch] it; a deleted def drops silently (returns null). */
    suspend fun launchById(defId: String): String? {
        val def = preferencesRepository.appWindowDefs.first().items.firstOrNull { it.id == defId }
            ?: return null
        return launch(def)
    }

    /**
     * Start [def]'s cage and present it. Returns null on success, or a
     * user-facing message — a read-only-fallback note when root mode was
     * requested but `fakeroot` is unavailable, or the launch error — for
     * the caller to surface.
     */
    suspend fun launch(def: AppWindowDef): String? = withContext(Dispatchers.IO) {
        // startAppWindow blocks (up to ~15s) polling the cage's VNC port, so
        // the whole thing runs off the caller's thread — the shortcut path
        // invokes this from MainScope (the main thread) and must not freeze
        // the UI that renders the present overlay.
        val resolution = def.resolution ?: preferencesRepository.appWindowDefaultResolution.first()
        val scale = def.scale ?: preferencesRepository.appWindowDefaultScale.first()
        val rooted = if (def.runAsRoot) desktopManager.ensureRunAsRoot() else false
        val session = desktopManager.startAppWindow(def.command, resolution, scale, runAsRoot = rooted)
        if (session.state != DesktopManager.DesktopState.RUNNING) {
            return@withContext "Couldn't launch ${def.label}: ${session.errorMessage ?: "failed to start"}"
        }
        presentationManager.presentAppWindow(
            host = "127.0.0.1",
            port = session.vncPort,
            sessionId = session.sessionId,
            caption = def.label,
            fullscreen = def.fullscreen,
            scale = scale,
            resolution = resolution,
        )
        // Preserve the def's own resolution/scale/runAsRoot choice (null = use global).
        preferencesRepository.upsertAppWindowDef(
            def.label, def.command, def.createdBy, def.fullscreen, def.resolution, def.scale, def.runAsRoot,
        )
        if (def.runAsRoot && !rooted) {
            "Root mode needs the fakeroot package — ${def.label} will run read-only"
        } else {
            null
        }
    }
}
