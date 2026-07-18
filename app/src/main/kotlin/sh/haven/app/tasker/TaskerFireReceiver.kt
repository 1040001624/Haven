package sh.haven.app.tasker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.haven.app.R
import sh.haven.app.agent.HeadlessSshExec
import javax.inject.Inject

/**
 * Fires the Locale/Tasker "Run command on a Haven server" action (#367).
 *
 * The host broadcasts [TaskerPlugin.ACTION_FIRE_SETTING] with the config
 * Bundle. Two modes:
 * - **overlay** — bring Haven to the front and run the command in a visible
 *   terminal so the user can watch it (routed through [MainActivity] via a
 *   `haven://run` deep link).
 * - **headless** — run over the exec channel via [HeadlessSshExec] and post a
 *   result notification with the exit code + a stdout snippet. When **block**
 *   is set, the ordered broadcast is held via [goAsync] until the command
 *   finishes, so a host configured to wait pauses the macro. (A `goAsync`
 *   window is bounded by the OS — see the block caveat below; long commands
 *   are better watched in overlay mode.)
 */
@AndroidEntryPoint
class TaskerFireReceiver : BroadcastReceiver() {

    @Inject lateinit var headlessSshExec: HeadlessSshExec

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskerPlugin.ACTION_FIRE_SETTING) return
        val bundle = TaskerPlugin.configFrom(intent)
        if (bundle == null) {
            Log.w(TAG, "ignoring fire with invalid/foreign config")
            return
        }
        val profileId = bundle.getString(TaskerPlugin.BUNDLE_PROFILE_ID)!!
        val label = bundle.getString(TaskerPlugin.BUNDLE_PROFILE_LABEL) ?: profileId
        val command = bundle.getString(TaskerPlugin.BUNDLE_COMMAND)!!
        val overlay = bundle.getBoolean(TaskerPlugin.BUNDLE_OVERLAY, false)
        val block = bundle.getBoolean(TaskerPlugin.BUNDLE_BLOCK, false)

        if (overlay) {
            // Interim (#367 Phase 1): bring Haven to the front so the user is in
            // the app while the command runs. The true live-terminal overlay —
            // streaming the command into a visible terminal tab — is a follow-up
            // (Phase 2). The command still runs headless below either way.
            runCatching {
                context.startActivity(
                    Intent(context, sh.haven.app.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        // goAsync keeps us alive; whether the host actually waits is decided by
        // whether we finish() before or after the command.
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                if (!block) {
                    // Fire-and-forget: let the macro continue immediately, run
                    // on the app-process scope so we survive the receiver.
                    pending.finish()
                    runAndNotify(appContext, profileId, label, command)
                } else {
                    // Hold the ordered broadcast until the command finishes.
                    runAndNotify(appContext, profileId, label, command)
                    pending.finish()
                }
            } catch (e: Exception) {
                Log.w(TAG, "fire failed: ${e.message}")
                runCatching { pending.finish() }
            }
        }
    }

    private suspend fun runAndNotify(
        context: Context,
        profileId: String,
        label: String,
        command: String,
    ) {
        val (title, text) = try {
            val outcome = headlessSshExec.run(profileId, command, TIMEOUT_MS)
            val r = outcome.exec
            val snippet = r.stdout.ifBlank { r.stderr }.trim().take(SNIPPET_CHARS)
            val head = if (r.timedOut) {
                context.getString(R.string.tasker_result_timed_out, label)
            } else {
                context.getString(R.string.tasker_result_exit, label, r.exitStatus)
            }
            head to snippet
        } catch (e: Exception) {
            context.getString(R.string.tasker_result_failed, label) to (e.message ?: "")
        }
        notify(context, title, text)
    }

    private fun notify(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.tasker_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        // NOTIFY is best-effort: on API 33+ without POST_NOTIFICATIONS this is
        // a no-op, which is fine — the command still ran.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) }
        }
    }

    companion object {
        private const val TAG = "TaskerFire"
        private const val CHANNEL_ID = "tasker_result"
        private const val NOTIF_ID = 0x7A5C
        private const val TIMEOUT_MS = 120_000L
        private const val SNIPPET_CHARS = 400

        /** App-process scope so a fire-and-forget command survives the receiver. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
