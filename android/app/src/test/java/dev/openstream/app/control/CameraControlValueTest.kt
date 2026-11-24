package dev.openstream.app.control

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraControlValueTest {
    @Test fun acceptsOnlyActualIntegerFieldsWithoutLossOrCoercion() {
        assertEquals(33_333_333L, strictLong(JSONObject("""{"time":33333333}"""), "time"))
        assertThrows(IllegalArgumentException::class.java) { strictLong(JSONObject("""{"time":"333"}"""), "time") }
        assertThrows(IllegalArgumentException::class.java) { strictInt(JSONObject("""{"value":2147483648}"""), "value") }
        assertThrows(IllegalArgumentException::class.java) { strictInt(JSONObject("""{"value":1.5}"""), "value") }
    }
    @Test fun acceptsNumericFocusButRejectsStringsAndBooleans() {
        assertEquals(.25f, strictFloat(JSONObject("""{"value":0.25}"""), "value"))
        assertThrows(IllegalArgumentException::class.java) { strictFloat(JSONObject("""{"value":"0.25"}"""), "value") }
        assertThrows(IllegalArgumentException::class.java) { strictBoolean(JSONObject("""{"enabled":"true"}"""), "enabled") }
    }
}
