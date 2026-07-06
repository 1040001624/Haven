package sh.haven.app.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * A small builder for a tool's JSON-Schema `inputSchema` (#mcp-backbone
 * Stage 5, Layer E), replacing the inline `JSONObject().apply { … }`
 * boilerplate. `objectSchema { string("path", "…", required = true) }` reads
 * as the tool's argument list instead of nested `put(…)` calls, and the
 * builder tracks the `required` set for you.
 *
 * Only the shapes the tool registry actually uses are modelled: object roots
 * with scalar / string-array / object-array properties and an optional enum.
 * Anything unusual drops to [SchemaBuilder.property], which takes a raw
 * pre-built schema. The emitted JSON is the same shape the hand-written
 * builders produced.
 */
internal fun objectSchema(build: SchemaBuilder.() -> Unit = {}): JSONObject =
    SchemaBuilder().apply(build).build()

internal class SchemaBuilder {
    private val properties = JSONObject()
    private val required = mutableListOf<String>()

    private fun add(name: String, schema: JSONObject, required: Boolean) {
        properties.put(name, schema)
        if (required) this.required.add(name)
    }

    private fun scalar(type: String, description: String?, enum: List<String>?): JSONObject =
        JSONObject().apply {
            put("type", type)
            if (description != null) put("description", description)
            if (enum != null) put("enum", JSONArray().apply { enum.forEach { put(it) } })
        }

    fun string(name: String, description: String? = null, enum: List<String>? = null, required: Boolean = false) =
        add(name, scalar("string", description, enum), required)

    fun integer(name: String, description: String? = null, required: Boolean = false) =
        add(name, scalar("integer", description, null), required)

    fun boolean(name: String, description: String? = null, required: Boolean = false) =
        add(name, scalar("boolean", description, null), required)

    fun number(name: String, description: String? = null, required: Boolean = false) =
        add(name, scalar("number", description, null), required)

    /** An array of strings — the common `items: { type: string }` case. */
    fun stringArray(name: String, description: String? = null, required: Boolean = false) =
        add(name, JSONObject().apply {
            put("type", "array")
            put("items", JSONObject().put("type", "string"))
            if (description != null) put("description", description)
        }, required)

    /** An array whose elements are objects described by [item]. */
    fun objectArray(
        name: String,
        description: String? = null,
        required: Boolean = false,
        item: SchemaBuilder.() -> Unit,
    ) = add(name, JSONObject().apply {
        put("type", "array")
        put("items", objectSchema(item))
        if (description != null) put("description", description)
    }, required)

    /** Escape hatch: a property carrying a pre-built schema (nested objects, unusual shapes). */
    fun property(name: String, schema: JSONObject, required: Boolean = false) = add(name, schema, required)

    fun build(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) put("required", JSONArray().apply { required.forEach { put(it) } })
    }
}
