package dev.carens.dashboard.telemetry

/**
 * Decides when to start and end trips automatically.
 *
 * Start: reliable GPS speed >= [startSpeedKmh] held for [startHoldMs]. The trip
 * is back-dated to when that began.
 * Stop: no reliable movement for [stopIdleMs]. The trip ends at the last
 * moment the car was moving, so the parked tail isn't counted.
 */
class AutoTripDetector(
    private val startSpeedKmh: Double = 10.0,
    private val startHoldMs: Long = 5_000,
    private val movingKmh: Double = 3.0,
    private val stopIdleMs: Long = 5 * 60_000,
) {
    sealed interface Decision {
        data object None : Decision
        data class Start(val sinceElapsedMs: Long) : Decision
        data class Stop(val endElapsedMs: Long) : Decision
    }

    private var aboveSinceMs: Long? = null
    private var lastMovingMs: Long? = null

    fun onTripStarted(nowElapsedMs: Long) {
        lastMovingMs = nowElapsedMs
        aboveSinceMs = null
    }

    fun onTripEnded() {
        lastMovingMs = null
        aboveSinceMs = null
    }

    fun onSample(timeMs: Long, speedKmh: Double?, reliable: Boolean, tripActive: Boolean): Decision {
        if (tripActive) {
            if (reliable && speedKmh != null && speedKmh >= movingKmh) lastMovingMs = timeMs
            return Decision.None
        }
        if (reliable && speedKmh != null && speedKmh >= startSpeedKmh) {
            val since = aboveSinceMs ?: timeMs.also { aboveSinceMs = it }
            if (timeMs - since >= startHoldMs) {
                aboveSinceMs = null
                return Decision.Start(since)
            }
        } else {
            aboveSinceMs = null
        }
        return Decision.None
    }

    fun onTick(nowElapsedMs: Long, tripActive: Boolean): Decision {
        if (!tripActive) return Decision.None
        val last = lastMovingMs ?: return Decision.None
        return if (nowElapsedMs - last >= stopIdleMs) Decision.Stop(last) else Decision.None
    }
}
