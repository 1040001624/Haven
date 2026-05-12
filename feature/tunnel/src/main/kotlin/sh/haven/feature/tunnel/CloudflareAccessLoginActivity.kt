package sh.haven.feature.tunnel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import sh.haven.core.security.JwtPayload

/**
 * Hosts a [WebView] that drives the Cloudflare Access IdP login flow
 * for a given Access hostname, then captures the `CF_Authorization`
 * cookie once auth completes.
 *
 * Cloudflare Access flow:
 *   1. We load `https://<hostname>/cdn-cgi/access/login/<hostname>`.
 *   2. CF redirects to the team's configured IdP (Okta / GitHub /
 *      Google / etc.) and back through `*.cloudflareaccess.com`.
 *   3. After successful auth, CF sets `CF_Authorization=<jwt>` as a
 *      cookie on the Access application origin (`<hostname>`) and
 *      redirects to the protected URL.
 *   4. On every page navigation we poll [CookieManager] for the cookie;
 *      the first time it appears we extract it, parse the JWT exp
 *      claim, and finish with the result.
 *
 * The WebView is created programmatically rather than via XML so the
 * `feature/tunnel` module doesn't need an `:android-resources`
 * declaration just for this one activity layout.
 *
 * The activity is declared in the *app* manifest (not this module's
 * manifest) because the WebView interacts with [CookieManager] on the
 * app's process — keeping the registration in `app/.../AndroidManifest.xml`
 * matches how other feature activities are exposed.
 */
class CloudflareAccessLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HOSTNAME = "sh.haven.feature.tunnel.HOSTNAME"
        const val EXTRA_JWT = "sh.haven.feature.tunnel.JWT"
        const val EXTRA_EXPIRES_AT = "sh.haven.feature.tunnel.EXPIRES_AT"
        const val EXTRA_ERROR = "sh.haven.feature.tunnel.ERROR"

        private const val TAG = "CFAccessLogin"
        private const val COOKIE_NAME = "CF_Authorization"
    }

    private lateinit var webView: WebView
    private lateinit var hostname: String
    @Volatile private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostname = intent.getStringExtra(EXTRA_HOSTNAME)
            ?.trim()
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.substringBefore('/')
            .orEmpty()

        if (hostname.isEmpty()) {
            finishWithError("No Cloudflare Access hostname provided")
            return
        }

        // Reset any leftover cookie from a prior attempt against the
        // same hostname so the IdP re-prompts. Without this, a previous
        // expired JWT lingers and we'd capture it again on first load.
        clearAccessCookie()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val header = TextView(this).apply {
            text = "Cloudflare Access · $hostname"
            setPadding(32, 32, 32, 16)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F2937"))
        }
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        webView = WebView(this).apply {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    tryCaptureJwt()
                }
            }
        }
        container.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val frame = FrameLayout(this).apply { addView(container) }
        setContentView(frame)

        webView.loadUrl("https://$hostname/cdn-cgi/access/login/$hostname")
    }

    private fun tryCaptureJwt() {
        if (resolved) return
        val cookies = CookieManager.getInstance().getCookie("https://$hostname") ?: return
        val jwt = parseAccessCookie(cookies) ?: return
        resolved = true

        val expiresAt = JwtPayload.parse(jwt)?.expiresAtSeconds ?: 0L
        Log.d(TAG, "captured CF_Authorization JWT (exp=$expiresAt)")

        val data = Intent().apply {
            putExtra(EXTRA_JWT, jwt)
            putExtra(EXTRA_EXPIRES_AT, expiresAt)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    /**
     * Extract the `CF_Authorization` value from a `Cookie:` header string
     * like `cf_clearance=…; CF_Authorization=eyJ…; other=…`. Returns
     * null if not present or empty.
     */
    private fun parseAccessCookie(header: String): String? {
        for (raw in header.split(';')) {
            val pair = raw.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0] == COOKIE_NAME) {
                val value = pair[1].trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun clearAccessCookie() {
        val cm = CookieManager.getInstance()
        val url = "https://$hostname"
        // Setting the cookie with an expired Max-Age is the documented
        // way to delete a single cookie via CookieManager (which has no
        // delete-one API).
        cm.setCookie(url, "$COOKIE_NAME=; Max-Age=0; Path=/")
        cm.flush()
    }

    private fun finishWithError(reason: String) {
        val data = Intent().apply { putExtra(EXTRA_ERROR, reason) }
        setResult(RESULT_CANCELED, data)
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
