package sh.haven.core.mcp

import org.json.JSONObject

/**
 * JSON-RPC 2.0 frame builders + protocol error types shared by Haven's MCP
 * server and its guest-MCP client (#mcp-backbone Stage 4 — Layer A), so the
 * two sides can't drift. Note: MCP revision 2025-06-18 REMOVED JSON-RPC
 * batching, so a single frame per body is the spec-correct shape, not a gap.
 */

/** A request (or, with a null [id], a notification) frame. */
fun jsonRpcRequest(id: Any?, method: String, params: JSONObject? = null): JSONObject =
    JSONObject().apply {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }

fun jsonRpcResult(id: Any?, result: Any?): String {
    val obj = JSONObject()
    obj.put("jsonrpc", "2.0")
    if (id != null) obj.put("id", id)
    obj.put("result", result ?: JSONObject.NULL)
    return obj.toString()
}

fun jsonRpcError(id: Any?, code: Int, message: String): String {
    val obj = JSONObject()
    obj.put("jsonrpc", "2.0")
    if (id != null) obj.put("id", id) else obj.put("id", JSONObject.NULL)
    obj.put("error", JSONObject().apply {
        put("code", code)
        put("message", message)
    })
    return obj.toString()
}

/** Lightweight error type carrying a JSON-RPC error code. */
open class McpError(val code: Int, message: String) : RuntimeException(message)

/**
 * Raised by the server's dispatcher when a non-initialize request presents an
 * `Mcp-Session-Id` it doesn't recognise. The HTTP layer maps this to **404**,
 * which is the streamable-HTTP-spec signal that tells the client to
 * re-`initialize` and retry — fixing the "won't reconnect after Haven restart
 * without dropping the Claude Code session" wedge.
 *
 * The error message is informational only; clients react to the 404
 * status, not the body.
 */
class SessionExpiredError :
    McpError(-32001, "MCP session expired; re-initialize")
