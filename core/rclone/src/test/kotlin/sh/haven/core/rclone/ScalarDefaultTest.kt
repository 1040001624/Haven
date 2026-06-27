package sh.haven.core.rclone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for #295: rclone option `Default` values that are JSON
 * arrays/objects must not leak into the field as literal "[]"/"{}".
 */
class ScalarDefaultTest {

    private fun withDefault(value: Any?) =
        JSONObject().apply { if (value != null) put("Default", value) }

    @Test
    fun `empty array default becomes empty string`() {
        assertEquals("", scalarDefault(withDefault(JSONArray())))
    }

    @Test
    fun `populated array default becomes empty string`() {
        assertEquals("", scalarDefault(withDefault(JSONArray(listOf("a", "b")))))
    }

    @Test
    fun `object default becomes empty string`() {
        assertEquals("", scalarDefault(withDefault(JSONObject().apply { put("k", "v") })))
    }

    @Test
    fun `missing default becomes empty string`() {
        assertEquals("", scalarDefault(JSONObject()))
    }

    @Test
    fun `json null default becomes empty string`() {
        assertEquals("", scalarDefault(withDefault(JSONObject.NULL)))
    }

    @Test
    fun `string default is preserved`() {
        assertEquals("hello", scalarDefault(withDefault("hello")))
    }

    @Test
    fun `numeric default is stringified`() {
        assertEquals("42", scalarDefault(withDefault(42)))
    }

    @Test
    fun `boolean default is stringified`() {
        assertEquals("true", scalarDefault(withDefault(true)))
    }
}
