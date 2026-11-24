package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorSpecTest {
    @Test
    fun rateDefaultsToFiveHz() {
        assertEquals(5, SensorSpec.rateHz(null))
    }

    @Test
    fun rateIsClampedToOneToFifty() {
        assertEquals(1, SensorSpec.rateHz("0"))
        assertEquals(1, SensorSpec.rateHz("-3"))
        assertEquals(50, SensorSpec.rateHz("51"))
        assertEquals(50, SensorSpec.rateHz("1000"))
        assertEquals(10, SensorSpec.rateHz("10"))
    }

    @Test
    fun fractionalRatesAreRounded() {
        assertEquals(2, SensorSpec.rateHz("1.6"))
        assertEquals(1, SensorSpec.rateHz("1.2"))
    }

    @Test
    fun ratesThatAreNotNumbersAreRejected() {
        assertNull(SensorSpec.rateHz(""))
        assertNull(SensorSpec.rateHz("fast"))
        assertNull(SensorSpec.rateHz("NaN"))
        assertNull(SensorSpec.rateHz("Infinity"))
    }

    @Test
    fun parseNeedsAKnownTypeAndAValidRate() {
        val plan = SensorSpec.parse("light", "20")
        assertEquals("light", plan?.name)
        assertEquals(20, plan?.rateHz)
        assertEquals(SensorSpec.parse("light", null)?.rateHz, SensorSpec.DEFAULT_RATE_HZ)
        assertNull(SensorSpec.parse(null, "5"))
        assertNull(SensorSpec.parse("thermometer", "5"))
        assertNull(SensorSpec.parse("STEPCOUNTER", "5"))
        assertNull(SensorSpec.parse("light", "x"))
    }

    @Test
    fun everyListedTypeIsKnown() {
        val names = listOf(
            "stepCounter", "stepDetector", "light", "pressure", "proximity", "magnetometer",
            "humidity", "ambientTemperature", "accelerometer", "gravity", "linearAcceleration", "rotationVector",
        )
        assertEquals(names.toSet(), SensorSpec.types.keys)
        assertTrue(names.all { SensorSpec.parse(it, null) != null })
    }

    @Test
    fun intervalMatchesRate() {
        assertEquals(200_000_000L, SensorSpec.intervalNs(5))
        assertEquals(1_000_000_000L, SensorSpec.intervalNs(1))
        assertEquals(20_000_000L, SensorSpec.intervalNs(50))
    }

    @Test
    fun onlyStepSensorsNeedActivityRecognition() {
        assertTrue(SensorSpec.needsActivityRecognition(SensorSpec.types.getValue("stepCounter")))
        assertTrue(SensorSpec.needsActivityRecognition(SensorSpec.types.getValue("stepDetector")))
        assertFalse(SensorSpec.needsActivityRecognition(SensorSpec.types.getValue("light")))
    }

    @Test
    fun sensorsArgsRequireTypeAndLimitLength() {
        assertEquals(
            "missing type",
            BridgeArgs.validate("sensors.subscribe", kotlinx.serialization.json.buildJsonObject {}),
        )
        assertNull(
            BridgeArgs.validate(
                "sensors.subscribe",
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("light"))
                },
            ),
        )
        assertEquals(
            "rateHz exceeds 8 characters",
            BridgeArgs.validate(
                "sensors.subscribe",
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("light"))
                    put("rateHz", kotlinx.serialization.json.JsonPrimitive("123456789"))
                },
            ),
        )
        assertEquals(
            "missing id",
            BridgeArgs.validate("sensors.unsubscribe", kotlinx.serialization.json.buildJsonObject {}),
        )
        assertNull(BridgeArgs.validate("sensors.list", kotlinx.serialization.json.buildJsonObject {}))
    }

    @Test
    fun throttleKeepsTheLastReadingOfAnInterval() {
        // Review: a reading inside the interval is held and sent when the interval ends, not lost.
        val t = SensorSpec.Throttle(100)
        assertEquals(SensorSpec.Throttle.Decision.Emit, t.offer(1_000))
        assertEquals(SensorSpec.Throttle.Decision.Defer(70), t.offer(1_030))
        assertEquals(SensorSpec.Throttle.Decision.Hold, t.offer(1_060))
        assertTrue(t.flush())
        assertFalse(t.flush())
        // The flushed reading counts as sent at 1_100.
        assertEquals(SensorSpec.Throttle.Decision.Defer(50), t.offer(1_150))
        assertTrue(t.flush())
        assertEquals(SensorSpec.Throttle.Decision.Emit, t.offer(1_300))
    }

    @Test
    fun throttlePassesSlowReadingsStraightThrough() {
        val t = SensorSpec.Throttle(100)
        assertEquals(SensorSpec.Throttle.Decision.Emit, t.offer(0))
        assertEquals(SensorSpec.Throttle.Decision.Emit, t.offer(100))
        assertEquals(SensorSpec.Throttle.Decision.Emit, t.offer(500))
        assertFalse(t.flush())
    }
}
