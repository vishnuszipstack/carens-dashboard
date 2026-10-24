package dev.carens.dashboard.telemetry

/**
 * Light exponential smoothing so the big number doesn't jitter by ±1 every
 * second, while still following real acceleration within a couple of fixes.
 * Speeds below [zeroBelowKmh] are shown as 0 (GPS reports a few km/h of noise
 * when parked).
 */
class SpeedFilter(
    private val alpha: Double = 0.6,
    private val zeroBelowKmh: Double = 2.0,
) {
    private var value: Double? = null

    fun reset() {
        value = null
    }

    fun update(rawKmh: Double): Double {
        val prev = value
        val next = if (prev == null) rawKmh else prev + alpha * (rawKmh - prev)
        value = next
        return if (next < zeroBelowKmh) 0.0 else next
    }
}

enum class GpsQuality {
    /** No fix, or the last fix is too old to trust. */
    NONE,
    POOR,
    FAIR,
    GOOD;

    companion object {
        fun fromAccuracy(accuracyM: Float?): GpsQuality = when {
            accuracyM == null -> POOR
            accuracyM <= 8f -> GOOD
            accuracyM <= 20f -> FAIR
            else -> POOR
        }
    }
}
