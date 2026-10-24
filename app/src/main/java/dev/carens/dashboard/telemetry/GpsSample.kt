package dev.carens.dashboard.telemetry

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One location fix, reduced to what the dashboard needs.
 *
 * [timeMs] is on the monotonic elapsed-realtime clock so trip maths is not
 * affected by the user (or the network) changing the wall clock mid-drive.
 */
data class GpsSample(
    val timeMs: Long,
    val wallTimeMs: Long,
    val lat: Double,
    val lon: Double,
    /** Ground speed from the GNSS chip; null when the fix carries no speed. */
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    /** Horizontal accuracy radius (68%); null when unknown. */
    val accuracyM: Float?,
    val altitudeM: Double?,
    val bearingDeg: Float?,
)

private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle distance in metres. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
