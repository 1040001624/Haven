package sh.haven.app.desktop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import sh.haven.app.MainActivity
import sh.haven.app.R
import sh.haven.core.data.preferences.AppWindowDef
import sh.haven.core.local.DesktopEntryParser
import sh.haven.core.local.GuestAppScanner
import sh.haven.core.local.InstalledApp
import sh.haven.core.local.LocalSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pins a saved app window to the home screen as a launcher shortcut whose
 * icon is the app's Linux desktop icon — resolved from the matching guest
 * `.desktop` entry, so it reads like a native app. On tap the shortcut
 * routes [ACTION_LAUNCH_APP_WINDOW] + [EXTRA_APP_WINDOW_ID] through
 * [MainActivity], which calls [AppWindowLauncher].
 *
 * On-demand (`requestPinShortcut`), unlike the auto dynamic-shortcut set
 * the Workspaces feature publishes — the user picks which apps get an icon.
 */
@Singleton
class AppWindowShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localSessionManager: LocalSessionManager,
) {
    /**
     * Ask the launcher to pin [def]. Returns false when the launcher
     * doesn't support pinned shortcuts (the system confirm dialog never
     * appears) so the caller can tell the user. Icon resolution is
     * best-effort; an unmatched command falls back to the Haven icon.
     */
    suspend fun pinToHomeScreen(def: AppWindowDef): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val icon = resolveIcon(def.command)?.let { IconCompat.createWithBitmap(it) }
            ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val shortcut = ShortcutInfoCompat.Builder(context, "appwin-${def.id}")
            .setShortLabel(def.label)
            .setLongLabel(def.label)
            .setIcon(icon)
            .setIntent(launchIntent(def.id))
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }
            .onFailure { Log.w(TAG, "requestPinShortcut failed: ${it.message}") }
            .getOrDefault(false)
    }

    private fun launchIntent(defId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_APP_WINDOW
            putExtra(EXTRA_APP_WINDOW_ID, defId)
            // CLEAR_TOP + SINGLE_TOP so an already-open MainActivity routes
            // through onNewIntent rather than spawning a duplicate.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    /**
     * Best-effort match of [command] to an installed app's `.desktop`
     * icon. Compares the launcher binary's basename (field codes
     * stripped), then falls back to an exact name match. Null when nothing
     * matches or the icon can't be decoded → caller uses the Haven icon.
     */
    private suspend fun resolveIcon(command: String): Bitmap? {
        val apps = runCatching { GuestAppScanner(localSessionManager.prootManager).scan().apps }
            .getOrNull() ?: return null
        val path = matchIcon(apps, command)?.iconPath ?: return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    companion object {
        private const val TAG = "AppWindowShortcuts"
        const val ACTION_LAUNCH_APP_WINDOW = "sh.haven.action.LAUNCH_APP_WINDOW"
        const val EXTRA_APP_WINDOW_ID = "sh.haven.extra.APP_WINDOW_ID"

        /**
         * Pick the installed app whose icon best fits [command]: a binary
         * basename match first, then an exact name match. Only apps with a
         * decodable icon are considered. Pure (no I/O) so it unit-tests.
         */
        internal fun matchIcon(apps: List<InstalledApp>, command: String): InstalledApp? {
            val withIcon = apps.filter { it.iconPath != null }
            val wantBin = binBasename(command)
            return withIcon.firstOrNull { binBasename(it.exec) == wantBin }
                ?: withIcon.firstOrNull { it.name.equals(command.trim(), ignoreCase = true) }
        }

        /** First token of a stripped command, basename only (e.g. "/usr/bin/gimp %U" → "gimp"). */
        private fun binBasename(command: String): String =
            DesktopEntryParser.stripExecCodes(command).substringBefore(' ').substringAfterLast('/')
    }
}
