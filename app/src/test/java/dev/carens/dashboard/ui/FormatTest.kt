package dev.carens.dashboard.ui

import dev.carens.dashboard.settings.SpeedUnit
import dev.carens.dashboard.telemetry.SpeedFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun distanceFormatting() {
        assertEquals("0.25", Format.distance(250.0, SpeedUnit.KMH))
        assertEquals("12.4", Format.distance(12_400.0, SpeedUnit.KMH))
        assertEquals("1.00", Format.distance(1_609.344, SpeedUnit.MPH))
    }

    @Test
    fun durationParts() {
        assertEquals("45" to "s", Format.durationParts(45_000))
        assertEquals("18" to "min", Format.durationParts(18 * 60_000L + 5_000))
        assertEquals("1:05" to "h", Format.durationParts(65 * 60_000L))
    }

    @Test
    fun compass() {
        assertEquals("N", Format.compass(0f))
        assertEquals("NE", Format.compass(44f))
        assertEquals("N", Format.compass(359f))
        assertEquals("W", Format.compass(-90f))
    }

    @Test
    fun speedFilterZeroesParkedNoiseAndTracksChanges() {
        val f = SpeedFilter(alpha = 0.6)
        assertEquals(0.0, f.update(1.5), 0.0)
        f.reset()
        var v = 0.0
        repeat(6) { v = f.update(60.0) }
        assertEquals(60.0, v, 0.5)
    }
}
