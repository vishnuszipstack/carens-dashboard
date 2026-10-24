package dev.carens.dashboard.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelTimerTest {

    @Test
    fun timesASteadyPullAwayWithInterpolation() {
        val timer = AccelTimer()
        val out = mutableListOf<AccelTimer.Result>()
        timer.onSample(0, 0.0, true)
        // +10 km/h per second: 60 at 6 s, 100 at 10 s.
        for (i in 1..12) out += timer.onSample(i * 1000L, i * 10.0 - 5.0, true)
        // Crossings are interpolated: 60 km/h between 6 s (55) and 7 s (65) → 6.5 s.
        assertEquals(60, out[0].targetKmh)
        assertEquals(6_500L, out[0].ms)
        assertEquals(100, out[1].targetKmh)
        assertEquals(10_500L, out[1].ms)
    }

    @Test
    fun brakingMidRunCancelsIt() {
        val timer = AccelTimer()
        timer.onSample(0, 0.0, true)
        timer.onSample(1000, 20.0, true)
        timer.onSample(2000, 40.0, true)
        timer.onSample(3000, 25.0, true) // braked
        val out = timer.onSample(4000, 70.0, true)
        assertTrue(out.isEmpty())
    }

    @Test
    fun unreliableGpsCancelsTheRun() {
        val timer = AccelTimer()
        timer.onSample(0, 0.0, true)
        timer.onSample(1000, 30.0, true)
        timer.onSample(2000, 50.0, false)
        assertTrue(timer.onSample(3000, 70.0, true).isEmpty())
    }
}
