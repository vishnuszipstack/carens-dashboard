package dev.carens.dashboard.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTripDetectorTest {

    @Test
    fun startsAfterSustainedSpeedAndBackdates() {
        val d = AutoTripDetector(startSpeedKmh = 10.0, startHoldMs = 5_000)
        val decisions = (0..6).map { i -> d.onSample(i * 1000L, 20.0, reliable = true, tripActive = false) }
        assertEquals(AutoTripDetector.Decision.None, decisions[4])
        assertEquals(AutoTripDetector.Decision.Start(0L), decisions[5])
    }

    @Test
    fun briefBurstOrUnreliableGpsDoesNotStart() {
        val d = AutoTripDetector(startSpeedKmh = 10.0, startHoldMs = 5_000)
        for (i in 0..3) d.onSample(i * 1000L, 20.0, reliable = true, tripActive = false)
        d.onSample(4_000L, 2.0, reliable = true, tripActive = false) // stopped again
        for (i in 5..9) {
            assertEquals(
                AutoTripDetector.Decision.None,
                d.onSample(i * 1000L, 20.0, reliable = false, tripActive = false),
            )
        }
    }

    @Test
    fun stopsAfterIdleAndEndsAtLastMovement() {
        val d = AutoTripDetector(stopIdleMs = 300_000)
        d.onTripStarted(0L)
        d.onSample(60_000L, 30.0, reliable = true, tripActive = true)
        d.onSample(61_000L, 0.0, reliable = true, tripActive = true)
        assertEquals(AutoTripDetector.Decision.None, d.onTick(359_000L, tripActive = true))
        assertEquals(AutoTripDetector.Decision.Stop(60_000L), d.onTick(360_000L, tripActive = true))
    }
}
