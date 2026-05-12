package sh.haven.core.tunnel

import org.json.JSONObject

/**
 * Storage envelope for Cloudflare Access SSH tunnel configs. Encoded into
 * [sh.haven.core.data.db.entities.TunnelConfig.configText] (which is then
 * encrypted at rest by the repository).
 *
 * Cloudflare Access is per-hostname: each tunnel is bound to one Access
 * application (a single SSH host behind a Cloudflare Tunnel). The cached
 * JWT is the user's IdP-signed `CF_Authorization` cookie obtained via the
 * in-app WebView login (or pasted from `cloudflared access token`).
 *
 * Format (UTF-8 JSON):
 * ```
 * {
 *   "hostname": "ssh.example.com",
 *   "teamDomain": "myteam.cloudflareaccess.com",
 *   "jwt": "eyJhbGciOi…",
 *   "jwtExpiresAt": 1737934800
 * }
 * ```
 *
 * `jwtExpiresAt` is a Unix epoch second derived from the JWT's `exp`
 * claim, stored alongside the JWT so the UI can flag expired rows
 * without parsing the token on every render. Unknown JSON keys are
 * ignored — adding fields (service-token credentials, last-used host)
 * won't break older clients reading newer blobs.
 */
data class CloudflareAccessConfigBlob(
    val hostname: String,
    val teamDomain: String,
    val jwt: String,
    val jwtExpiresAt: Long,
) {
    fun encode(): ByteArray {
        val json = JSONObject().apply {
            put("hostname", hostname)
            put("teamDomain", teamDomain)
            put("jwt", jwt)
            put("jwtExpiresAt", jwtExpiresAt)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun isJwtExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        jwtExpiresAt in 1 until nowEpochSeconds

    companion object {
        /**
         * Decode bytes from [TunnelConfig.configText]. Throws
         * [IllegalArgumentException] on malformed or incomplete JSON —
         * unlike Tailscale's blob there's no legacy raw-bytes format to
         * fall back to.
         */
        fun parse(bytes: ByteArray): CloudflareAccessConfigBlob {
            val text = String(bytes, Charsets.UTF_8).trim()
            val json = try {
                JSONObject(text)
            } catch (t: Throwable) {
                throw IllegalArgumentException("CloudflareAccessConfigBlob: not valid JSON", t)
            }
            val hostname = json.optString("hostname").trim()
            val teamDomain = json.optString("teamDomain").trim()
            val jwt = json.optString("jwt").trim()
            require(hostname.isNotEmpty()) { "CloudflareAccessConfigBlob: missing hostname" }
            require(jwt.isNotEmpty()) { "CloudflareAccessConfigBlob: missing jwt" }
            return CloudflareAccessConfigBlob(
                hostname = hostname,
                teamDomain = teamDomain,
                jwt = jwt,
                jwtExpiresAt = json.optLong("jwtExpiresAt", 0L),
            )
        }
    }
}
