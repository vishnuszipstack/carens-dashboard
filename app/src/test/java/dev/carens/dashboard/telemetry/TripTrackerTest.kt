package dev.carens.dashboard.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrackerTest {

    // ~11.12 m per 0.0001° of latitude.
    private fun sample(
        t: Long,
        lat: Double,
        lon: Double = 76.2,
        speedMps: Float? = 11.1f,
        acc: Float? = 5f,
    ) = GpsSample(
        timeMs = t,
        wallTimeMs = t,
        lat = lat,
        lon = lon,
        speedMps = speedMps,
        speedAccuracyMps = 0.5f,
        accuracyM = acc,
        altitudeM = null,
        bearingDeg = 0f,
    )

    @Test
    fun steadyDriveAccumulatesDistanceAndMovingTime() {
        val tracker = TripTracker(startElapsedMs = 0, startWallMs = 0)
        for (i in 0..60) tracker.onSample(sample(i * 1000L, 10.5 + i * 0.0001))
        // 60 segments × ~11.12 m
        assertEquals(667.0, tracker.distanceM, 667.0 * 0.03)
        assertEquals(60_000L, tracker.movingMs)
        assertEquals(11.1 * 3.6, tracker.maxSpeedKmh, 0.01)
        val avg = tracker.avgMovingKmh!!
        assertEquals(40.0, avg, 2.0)
    }

    @Test
    fun stationaryJitterAddsNoDistance() {
        val tracker = TripTracker(0, 0)
        for (i in 0..120) {
            val wobble = if (i % 2 == 0) 0.00002 else -0.00002 // ±2.2 m
            tracker.onSample(sample(i * 1000L, 10.5 + wobble, speedMps = 0.3f, acc = 8f))
        }
        assertEquals(0.0, tracker.distanceM, 0.001)
        assertEquals(0L, tracker.movingMs)
        assertNull(tracker.avgMovingKmh)
    }

    @Test
    fun singleOutlierIsIgnored() {
        val tracker = TripTracker(0, 0)
        for (i in 0..10) tracker.onSample(sample(i * 1000L, 10.5 + i * 0.0001))
        tracker.onSample(sample(11_000L, 10.6)) // ~11 km jump in one second
        for (i in 12..20) tracker.onSample(sample(i * 1000L, 10.5 + i * 0.0001))
        // 20 normal steps of ~11.12 m; the outlier contributes nothing.
        assertEquals(222.4, tracker.distanceM, 222.4 * 0.03)
    }

    @Test
    fun longGpsOutageIsNotBridged() {
        val tracker = TripTracker(0, 0)
        for (i in 0..10) tracker.onSample(sample(i * 1000L, 10.5 + i * 0.0001))
        val before = tracker.distanceM
        // Reappears 200 s later, 2 km away.
        tracker.onSample(sample(210_000L, 10.52))
        assertEquals(before, tracker.distanceM, 0.001)
        assertTrue(tracker.gapMs >= 200_000L)
    }

    @Test
    fun shortTunnelIsBridgedAndCountedAsMoving() {
        val tracker = TripTracker(0, 0)
        tracker.onSample(sample(0L, 10.5))
        tracker.onSample(sample(1_000L, 10.5001))
        // 30 s tunnel at ~11 m/s ≈ 333 m
        tracker.onSample(sample(31_000L, 10.5031))
        assertEquals(344.0, tracker.distanceM, 344.0 * 0.03)
        assertEquals(31_000L, tracker.movingMs)
        assertEquals(30_000L, tracker.gapMs)
    }

    @Test
    fun inaccurateFixesDoNotCountDistance() {
        val tracker = TripTracker(0, 0)
        for (i in 0..20) tracker.onSample(sample(i * 1000L, 10.5 + i * 0.0001, acc = 60f))
        assertEquals(0.0, tracker.distanceM, 0.001)
        assertEquals(0.0, tracker.maxSpeedKmh, 0.001)
    }
}
