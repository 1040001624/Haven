package sh.haven.core.stepca

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.db.entities.StepCaConfig
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

/**
 * Talks to a step-ca instance over its HTTPS API. Pins TLS to the
 * user-supplied root cert PEM via [PinnedTls] — never falls back to
 * the system trust store.
 *
 * Phase 2a uses two endpoints:
 *  - `GET  /health`        — for the Settings "Test connection" affordance.
 *  - `POST /1.0/sign-ssh`  — to mint a signed SSH cert.
 */
@Singleton
class StepCaApiClient @Inject constructor() {

    /**
     * Fetch the SSH user/host CA public keys from step-ca's
     * `/1.0/ssh/config` endpoint. Used by the Settings dialog's
     * "Discover host CA" button so the user doesn't have to paste the
     * key by hand. Some step-ca deployments don't expose this endpoint
     * to unauthenticated clients — manual paste is the fallback.
     * (#133 phase 2b)
     */
    suspend fun fetchSshConfig(caConfig: StepCaConfig): SshConfigResult = withContext(Dispatchers.IO) {
        val pinned = try {
            PinnedTls.fromPem(caConfig.rootCertPem)
        } catch (e: Throwable) {
            return@withContext SshConfigResult.Failure("Invalid root cert PEM: ${e.message}")
        }
        val url = URL(caConfig.caUrl.trimEnd('/') + "/1.0/ssh/config")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinned.socketFactory
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                return@withContext SshConfigResult.Failure("HTTP $rc: ${err.take(300)}")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(resp)
            // step-ca returns hostKey/userKey as either base64 strings or
            // OpenSSH single-line text — be flexible. Most installs emit
            // PEM-shaped values.
            val hostKey = json.optString("hostKey", "").ifEmpty { null }
            val userKey = json.optString("userKey", "").ifEmpty { null }
            if (hostKey == null && userKey == null) {
                return@withContext SshConfigResult.Failure(
                    "step-ca /1.0/ssh/config returned no hostKey or userKey",
                )
            }
            SshConfigResult.Success(hostKey = hostKey, userKey = userKey)
        } catch (e: Throwable) {
            SshConfigResult.Failure("Network error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** Lightweight reachability check for the Settings UI. */
    suspend fun testConnection(caConfig: StepCaConfig): TestResult = withContext(Dispatchers.IO) {
        val pinned = try {
            PinnedTls.fromPem(caConfig.rootCertPem)
        } catch (e: Throwable) {
            return@withContext TestResult.BadRootCert(e.message ?: "Invalid root cert PEM")
        }
        val url = URL(caConfig.caUrl.trimEnd('/') + "/health")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinned.socketFactory
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
        }
        try {
            val rc = conn.responseCode
            if (rc in 200..299) TestResult.Ok else TestResult.HttpError(rc, conn.responseMessage ?: "")
        } catch (e: Throwable) {
            TestResult.NetworkError(e.message ?: "Network error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Mint a signed SSH user certificate. Returns the cert bytes ready
     * to drop into [sh.haven.core.data.db.entities.SshKey.certificateBytes].
     */
    suspend fun signSsh(
        caConfig: StepCaConfig,
        idToken: String,
        publicKeyOpenSsh: String,
        keyId: String,
        principalsOverride: List<String>? = null,
    ): SignSshResult = withContext(Dispatchers.IO) {
        val pinned = try {
            PinnedTls.fromPem(caConfig.rootCertPem)
        } catch (e: Throwable) {
            return@withContext SignSshResult.Failure("Invalid root cert PEM: ${e.message}")
        }

        val publicKeyB64 = extractOpenSshPublicKeyBase64(publicKeyOpenSsh)
            ?: return@withContext SignSshResult.Failure("Public key is not OpenSSH format")

        val principals = principalsOverride
            ?: caConfig.defaultPrincipals.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val body = JSONObject().apply {
            put("ott", idToken)
            put("publicKey", publicKeyB64)
            put("certType", "user")
            put("keyID", keyId)
            if (principals.isNotEmpty()) {
                put("principals", JSONArray().apply { principals.forEach { put(it) } })
            }
        }.toString()

        val url = URL(caConfig.caUrl.trimEnd('/') + "/1.0/sign-ssh")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinned.socketFactory
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val rc = conn.responseCode
            if (rc !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                return@withContext SignSshResult.Failure("step-ca HTTP $rc: ${err.take(500)}")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(resp)
            val crt = json.optString("crt", "")
            if (crt.isEmpty()) {
                return@withContext SignSshResult.Failure("step-ca response missing 'crt' field")
            }
            SignSshResult.Success(crt.toByteArray(Charsets.US_ASCII))
        } catch (e: Throwable) {
            SignSshResult.Failure("step-ca request failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Pull the base64 wire-format public key out of an OpenSSH single-line
     * key (`ssh-ed25519 AAAA... [comment]`). Returns null if the input
     * isn't recognisably OpenSSH.
     */
    private fun extractOpenSshPublicKeyBase64(openSsh: String): String? {
        val parts = openSsh.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return null
        return parts[1].takeIf { it.isNotEmpty() }
    }

    sealed interface TestResult {
        data object Ok : TestResult
        data class BadRootCert(val reason: String) : TestResult
        data class HttpError(val code: Int, val message: String) : TestResult
        data class NetworkError(val message: String) : TestResult
    }

    sealed interface SshConfigResult {
        data class Success(val hostKey: String?, val userKey: String?) : SshConfigResult
        data class Failure(val message: String) : SshConfigResult
    }

    sealed interface SignSshResult {
        data class Success(val certBytes: ByteArray) : SignSshResult {
            override fun equals(other: Any?): Boolean =
                other is Success && certBytes.contentEquals(other.certBytes)

            override fun hashCode(): Int = certBytes.contentHashCode()
        }
        data class Failure(val message: String) : SignSshResult
    }
}
