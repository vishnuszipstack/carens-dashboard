package dev.carens.dashboard.ui

import dev.carens.dashboard.settings.SpeedUnit
import java.util.Locale
import kotlin.math.roundToInt

object Format {
    fun speed(value: Double): String = value.roundToInt().toString()

    /** Distance in the unit's distance label (km or mi), without the label. */
    fun distance(meters: Double, unit: SpeedUnit): String {
        val v = meters / unit.metersPerDistanceUnit
        return when {
            v < 10 -> String.format(Locale.US, "%.2f", v)
            v < 100 -> String.format(Locale.US, "%.1f", v)
            else -> String.format(Locale.US, "%.0f", v)
        }
    }

    /** Compact value/unit pair for dashboard tiles: ("45","s"), ("18","min"), ("1:05","h"). */
    fun durationParts(ms: Long): Pair<String, String> {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d:%02d", h, m) to "h"
            m > 0 -> m.toString() to "min"
            else -> s.toString() to "s"
        }
    }

    fun duration(ms: Long): String = durationParts(ms).let { (v, u) -> "$v $u" }

    fun elevation(meters: Double, unit: SpeedUnit): Pair<String, String> =
        if (unit == SpeedUnit.MPH) (meters * 3.28084).roundToInt().toString() to "ft"
        else meters.roundToInt().toString() to "m"

    fun compass(bearingDeg: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((bearingDeg % 360f) + 360f) % 360f
        return dirs[((normalized + 22.5f) / 45f).toInt() % 8]
    }
}
