package sh.haven.mail.bridge

import sh.haven.rclone.binding.mailbridge.Mailbridge

/**
 * Thin Kotlin wrapper around the gomobile-generated bindings for the Go
 * `mailbridge` module (Proton Mail via rclone's go-proton-api). Mirrors
 * [sh.haven.rclone.bridge.RcloneBridge]: a single [rpc] entry point with
 * JSON in/out, plus typed convenience helpers.
 *
 * State (logged-in, unlocked Proton sessions) lives in the Go process,
 * keyed by an opaque [sessionId] chosen by the caller. Outgoing HTTPS is
 * routed through a per-profile SOCKS5 tunnel when [socks] is supplied at
 * login — see TunnelResolver.socksEndpoint.
 */
object MailBridge {

    data class RpcResult(
        val status: Int,
        val output: String,
    ) {
        val isOk: Boolean get() = status == 200
    }

    /**
     * Call a mailbridge method.
     *
     * @param method one of: login, listFolders, listMessages, getMessage, send, logout
     * @param input  JSON parameters (see the Go handlers for each shape)
     */
    fun rpc(method: String, input: String = "{}"): RpcResult {
        val result = Mailbridge.mbRPC(method, input)
        return RpcResult(status = result.status.toInt(), output = result.output)
    }

    /**
     * SRP login + optional TOTP 2FA + keyring unlock. On success the result
     * JSON carries { uid, accessToken, refreshToken, saltedKeyPass } for the
     * caller to persist (encrypted) and resume later.
     *
     * Status 412 with output `{"error":"2fa_required"}` /
     * `{"error":"mailbox_password_required"}` signals the caller to re-prompt
     * and retry with [twoFA] / [mailboxPassword] filled in.
     */
    fun login(
        sessionId: String,
        username: String,
        password: String,
        mailboxPassword: String? = null,
        twoFA: String? = null,
        appVersion: String? = null,
        hostUrl: String? = null,
        socks: String? = null,
    ): RpcResult = rpc("login", jsonObject(
        "sessionId" to sessionId,
        "username" to username,
        "password" to password,
        "mailboxPassword" to mailboxPassword,
        "twoFA" to twoFA,
        "appVersion" to appVersion,
        "hostUrl" to hostUrl,
        "socks" to socks,
    ))

    /** List folders/labels for the session as a JSON array. */
    fun listFolders(sessionId: String): RpcResult =
        rpc("listFolders", jsonObject("sessionId" to sessionId))

    /** List message metadata for a label as a JSON array. */
    fun listMessages(sessionId: String, labelId: String, desc: Boolean = true): RpcResult =
        rpc("listMessages", jsonObject(
            "sessionId" to sessionId,
            "labelID" to labelId,
            "desc" to desc,
        ))

    /** Fetch and decrypt one message; output `{ "rfc822": "<base64>" }`. */
    fun getMessage(sessionId: String, messageId: String): RpcResult =
        rpc("getMessage", jsonObject(
            "sessionId" to sessionId,
            "messageID" to messageId,
        ))

    /** Revoke and drop the Proton session. */
    fun logout(sessionId: String): RpcResult =
        rpc("logout", jsonObject("sessionId" to sessionId))

    // Minimal JSON object builder — avoids pulling a JSON lib into this thin
    // wrapper. Null values are omitted; strings/booleans are escaped.
    private fun jsonObject(vararg pairs: Pair<String, Any?>): String =
        pairs.filter { it.second != null }.joinToString(
            prefix = "{", postfix = "}", separator = ","
        ) { (k, v) ->
            val value = when (v) {
                is Boolean -> v.toString()
                else -> "\"${escape(v.toString())}\""
            }
            "\"${escape(k)}\":$value"
        }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}
