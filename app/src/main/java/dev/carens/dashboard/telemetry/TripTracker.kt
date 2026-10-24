package dev.carens.dashboard.telemetry

import kotlin.math.max

/**
 * Accumulates trip statistics from GPS fixes. Pure Kotlin, no Android types,
 * so it can be unit-tested and shared with a future Android Auto screen.
 *
 * Rules, in short:
 *  - Distance only from fixes with accuracy <= [Config.maxAccuracyForDistanceM].
 *  - Stationary jitter is ignored: a segment counts only if the car is moving
 *    or the jump is larger than the fixes' own uncertainty.
 *  - Physically implausible jumps are dropped as outliers.
 *  - After a GPS outage longer than [Config.maxGapToBridgeMs] no distance is
 *    invented across the gap; shorter outages (tunnels) are bridged with the
 *    straight-line distance, which can only under-count.
 */
class TripTracker(
    val startElapsedMs: Long,
    val startWallMs: Long,
    private val config: Config = Config(),
) {
    data class Config(
        val maxAccuracyForDistanceM: Float = 25f,
        val maxSpeedAccuracyForMaxMps: Float = 2.5f,
        val movingThresholdKmh: Double = 3.0,
        val maxPlausibleKmh: Double = 250.0,
        val gapThresholdMs: Long = 5_000,
        val maxGapToBridgeMs: Long = 120_000,
        val maxConsecutiveOutliers: Int = 3,
    )

    var distanceM: Double = 0.0
        private set
    var movingMs: Long = 0
        private set
    var gapMs: Long = 0
        private set
    var maxSpeedKmh: Double = 0.0
        private set

    private var anchor: GpsSample? = null
    private var lastSampleMs: Long? = null
    private var lastGoodMs: Long? = null
    private var lastSpeedKmh: Double? = null
    private var outlierStreak = 0

    fun elapsedMs(nowElapsedMs: Long): Long = (nowElapsedMs - startElapsedMs).coerceAtLeast(0)

    /** Average over moving time only; null until there's enough to be meaningful. */
    val avgMovingKmh: Double?
        get() = if (movingMs < 10_000 || distanceM < 50) null else distanceM / (movingMs / 1000.0) * 3.6

    fun onSample(s: GpsSample) {
        if (s.timeMs < startElapsedMs) return
        val prevMs = lastSampleMs
        if (prevMs != null && s.timeMs <= prevMs) return // duplicate / out of order

        val speedKmh = s.speedMps?.let { it * 3.6 }
        val prevSpeed = lastSpeedKmh
        lastSampleMs = s.timeMs
        lastSpeedKmh = speedKmh

        accountTime(s.timeMs - (prevMs ?: startElapsedMs), speedKmh, prevSpeed)

        val acc = s.accuracyM ?: return
        if (acc > config.maxAccuracyForDistanceM) return

        if (speedKmh != null && speedKmh <= config.maxPlausibleKmh &&
            (s.speedAccuracyMps == null || s.speedAccuracyMps <= config.maxSpeedAccuracyForMaxMps)
        ) {
            maxSpeedKmh = max(maxSpeedKmh, speedKmh)
        }

        val a = anchor
        val lastGood = lastGoodMs
        lastGoodMs = s.timeMs
        if (a == null || lastGood == null || s.timeMs - lastGood > config.maxGapToBridgeMs) {
            anchor = s
            outlierStreak = 0
            return
        }

        val d = distanceMeters(a.lat, a.lon, s.lat, s.lon)
        val dtSeconds = (s.timeMs - a.timeMs) / 1000.0
        val impliedKmh = d / dtSeconds * 3.6
        if (impliedKmh > config.maxPlausibleKmh) {
            outlierStreak++
            if (outlierStreak >= config.maxConsecutiveOutliers) {
                // The anchor itself was probably the bad fix; start over from here.
                anchor = s
                outlierStreak = 0
            }
            return
        }
        outlierStreak = 0

        val segmentSpeed = speedKmh ?: impliedKmh
        val anchorAcc = a.accuracyM ?: acc
        val moved = (segmentSpeed >= config.movingThresholdKmh && d >= 1.0) || d > max(acc, anchorAcc)
        if (moved) {
            distanceM += d
            anchor = s
        }
        // else: jitter while stationary. Keep the anchor so slow creep still adds up later.
    }

    private fun accountTime(dt: Long, speedKmh: Double?, prevSpeedKmh: Double?) {
        if (dt <= 0) return
        val movingNow = speedKmh != null && speedKmh >= config.movingThresholdKmh
        if (dt > config.gapThresholdMs) {
            gapMs += dt
            // Moving on both sides of the outage (tunnel, flyover): count it as moving time.
            val movingBefore = prevSpeedKmh != null && prevSpeedKmh >= config.movingThresholdKmh
            if (movingNow && movingBefore) movingMs += dt
        } else if (movingNow) {
            movingMs += dt
        }
    }
}
