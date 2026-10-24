package dev.carens.dashboard.nav

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** A search result or saved place. */
data class Destination(
    val name: String,
    val address: String?,
    val point: LatLon,
)

enum class Maneuver {
    DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN, ROUNDABOUT, MERGE, RAMP_LEFT, RAMP_RIGHT, FORK_LEFT, FORK_RIGHT, ARRIVE;

    /** Single-glyph arrow for places that can only draw text (the car picture). */
    val glyph: String
        get() = when (this) {
            DEPART, STRAIGHT, MERGE -> "⬆"
            SLIGHT_LEFT, FORK_LEFT, RAMP_LEFT -> "↖"
            LEFT, SHARP_LEFT -> "↰"
            SLIGHT_RIGHT, FORK_RIGHT, RAMP_RIGHT -> "↗"
            RIGHT, SHARP_RIGHT -> "↱"
            UTURN -> "↶"
            ROUNDABOUT -> "⟳"
            ARRIVE -> "🏁"
        }
}

data class RouteStep(
    val maneuver: Maneuver,
    /** Spoken/displayed instruction, e.g. "Turn left onto MG Road". */
    val instruction: String,
    /** Short form for tight spaces, e.g. "MG Road". */
    val roadName: String,
    /** Where the maneuver happens. */
    val location: LatLon,
    /** Length of the stretch *after* this maneuver, metres. */
    val distanceM: Double,
    val durationS: Double,
)

data class Route(
    val points: List<LatLon>,
    val steps: List<RouteStep>,
    val distanceM: Double,
    val durationS: Double,
)

data class NavState(
    val destination: Destination,
    val route: Route,
    /** Index of the step whose maneuver is coming up next. */
    val nextStepIndex: Int,
    val distanceToNextM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val etaWallMs: Long,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
) {
    val nextStep: RouteStep get() = route.steps[nextStepIndex.coerceIn(0, route.steps.lastIndex)]
    val stepAfter: RouteStep? get() = route.steps.getOrNull(nextStepIndex + 1)
}

/** Flat-earth helpers; accurate to well under a metre at city scale. */
object Geo {
    private const val M_PER_DEG = 111_320.0

    /** Distance in metres from p to the segment a–b. */
    fun distanceToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k * M_PER_DEG
        val ay = (a.lat - p.lat) * M_PER_DEG
        val bx = (b.lon - p.lon) * k * M_PER_DEG
        val by = (b.lat - p.lat) * M_PER_DEG
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / len2))
        val x = ax + t * dx
        val y = ay + t * dy
        return sqrt(x * x + y * y)
    }

    /** Smallest distance from p to the polyline, searching around [hint] first for speed. */
    fun distanceToPolyline(p: LatLon, points: List<LatLon>): Double {
        if (points.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until points.lastIndex) {
            val d = distanceToSegment(p, points[i], points[i + 1])
            if (d < best) best = d
        }
        return best
    }
}

/** Formats a distance for navigation: "350 m", "1.2 km" (or ft/mi). */
fun navDistance(m: Double, imperial: Boolean): String = if (imperial) {
    val ft = m * 3.28084
    if (ft < 1000) "${(ft / 50).toInt() * 50} ft" else String.format(java.util.Locale.US, "%.1f mi", m / 1609.344)
} else {
    when {
        m < 100 -> "${(m / 10).toInt() * 10} m"
        m < 1000 -> "${(m / 50).toInt() * 50} m"
        m < 10_000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000)
        else -> "${(m / 1000).toInt()} km"
    }
}
