package sh.haven.feature.tunnel

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * ActivityResultContract for the Cloudflare Access in-app login flow.
 *
 * Input: the Access application [hostname] (e.g. `ssh.example.com`).
 * Output: a [Result] sealed type — success carries the captured JWT plus
 * its parsed expiry; cancel and failure are distinct so the form can
 * surface failures as snackbars while treating cancel as silent.
 */
class CloudflareAccessLoginContract :
    ActivityResultContract<CloudflareAccessLoginContract.Input, CloudflareAccessLoginContract.Result>() {

    data class Input(val hostname: String)

    sealed class Result {
        data class Success(val jwt: String, val expiresAtSeconds: Long) : Result()
        data object Cancelled : Result()
        data class Failed(val reason: String) : Result()
    }

    override fun createIntent(context: Context, input: Input): Intent =
        Intent(context, CloudflareAccessLoginActivity::class.java).apply {
            putExtra(CloudflareAccessLoginActivity.EXTRA_HOSTNAME, input.hostname)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Result {
        if (resultCode == android.app.Activity.RESULT_CANCELED && intent == null) {
            return Result.Cancelled
        }
        val jwt = intent?.getStringExtra(CloudflareAccessLoginActivity.EXTRA_JWT)
        if (jwt != null && jwt.isNotBlank()) {
            return Result.Success(
                jwt = jwt,
                expiresAtSeconds = intent.getLongExtra(
                    CloudflareAccessLoginActivity.EXTRA_EXPIRES_AT, 0L,
                ),
            )
        }
        val reason = intent?.getStringExtra(CloudflareAccessLoginActivity.EXTRA_ERROR)
        return if (reason != null) Result.Failed(reason) else Result.Cancelled
    }
}
