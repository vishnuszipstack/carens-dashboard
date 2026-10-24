package dev.carens.dashboard.car

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import dev.carens.dashboard.info.NowPlaying
import dev.carens.dashboard.info.PlaceInfo
import dev.carens.dashboard.info.WeatherInfo
import dev.carens.dashboard.nav.NavState
import dev.carens.dashboard.nav.navDistance
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.settings.SpeedUnit
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.GpsQuality
import dev.carens.dashboard.ui.Format
import java.util.Date
import kotlin.math.roundToInt

/**
 * What the Android Auto screen shows, built from the same data the phone uses.
 * Shared by the media service (the real car screen) and the in-app preview.
 */
object CarView {
    data class Content(
        val model: DashArtModel,
        /** Changes whenever the picture would look different. */
        val artKey: String,
        val title: String,
        val subtitle: String,
        val third: String,
    )

    fun build(
        context: Context,
        t: DashboardTelemetry,
        s: DashSettings,
        weather: WeatherInfo?,
        place: PlaceInfo?,
        music: NowPlaying?,
        nav: NavState?,
        map: Bitmap?,
        hasPermission: Boolean,
    ): Content {
        val u = s.unit
        val imperial = u == SpeedUnit.MPH
        val speed = t.gpsSpeedKmh?.let { u.fromKmh(it).roundToInt() }
        val over = s.speedWarningEnabled && (t.gpsSpeedKmh ?: 0.0) > s.speedWarningKmh
        val status = when {
            !hasPermission -> "Location needed"
            !t.serviceRunning -> "Open app on phone"
            t.locationError != null -> "Location off"
            !t.hasFix -> "Waiting for GPS"
            t.isStale -> "GPS lost"
            t.gpsQuality == GpsQuality.POOR -> "Weak GPS"
            else -> null
        }
        val trip = t.trip
        val tripLine = trip?.let {
            "Trip ${Format.distance(it.distanceM, u)} ${u.distanceLabel} · ${Format.duration(it.elapsedMs)}" +
                (it.avgMovingKmh?.let { a -> " · avg ${u.fromKmh(a).roundToInt()}" } ?: "")
        } ?: if (s.autoTrip) "No trip · starts when you drive" else "No trip running"
        val musicLine = music?.let { listOfNotNull(it.title, it.artist).joinToString(" – ") }
        val clock = DateFormat.getTimeFormat(context).format(Date())
        val navLine = nav?.let {
            when {
                it.arrived -> "🏁 Arrived · ${it.destination.name}"
                it.rerouting -> "Rerouting…"
                else -> "${it.nextStep.maneuver.glyph} ${navDistance(it.distanceToNextM, imperial)}" +
                    (it.nextStep.roadName.takeIf { r -> r.isNotBlank() }?.let { r -> " · $r" } ?: "")
            }
        }
        val model = DashArtModel(
            speed = speed,
            unitLabel = u.label,
            fraction = ((speed ?: 0) / u.gaugeMax).toFloat(),
            over = over,
            status = status,
            clock = clock,
            weatherEmoji = weather?.emoji,
            weatherTemp = weather?.tempLabel,
            tripLine = tripLine,
            musicLine = musicLine,
            placeLine = if (navLine != null) null else place?.label,
            navLine = navLine,
            bearing = t.bearingDegrees,
            map = map,
        )
        val artKey = listOf(
            speed, over, status, clock, weather?.tempLabel, weather?.code, tripLine, musicLine,
            place?.label, t.bearingDegrees?.roundToInt()?.div(10), u.name, navLine, map?.generationId,
        ).joinToString("|")
        val title = if (nav != null && !nav.arrived) {
            "${navDistance(nav.distanceToNextM, imperial)} · ${nav.nextStep.instruction}"
        } else {
            buildString {
                append(speed?.let { "$it ${u.label}" } ?: "-- ${u.label}")
                weather?.let { append("  ·  ${it.tempLabel} ${it.description}") }
            }
        }
        val subtitle = musicLine?.let { "$tripLine  ·  ♪ $it" } ?: tripLine
        val third = place?.label ?: status ?: "GPS speed"
        return Content(model, artKey, title, subtitle, third)
    }
}
