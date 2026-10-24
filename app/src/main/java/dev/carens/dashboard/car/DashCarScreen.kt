package dev.carens.dashboard.car

import android.content.Intent
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.carens.dashboard.DashApp
import dev.carens.dashboard.MainActivity
import dev.carens.dashboard.location.LocationPermissions
import dev.carens.dashboard.service.DrivingService
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.GpsQuality
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.ui.Format
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Live speed and trip read-out on the car display.
 *
 * Android Auto limits how many *new* templates an app may push, but a refresh
 * doesn't count as long as the template title, the number of rows and each
 * row's title stay the same. So row titles are fixed labels and only the row
 * text (the values) changes. Refreshes are also throttled.
 */
class DashCarScreen(carContext: CarContext) : Screen(carContext) {

    private var telemetry = TelemetryHub.telemetry.value
    private var settings = DashSettings()
    private var lastRendered: List<String>? = null
    private var lastInvalidateMs = 0L
    private var job: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val container = (carContext.applicationContext as DashApp).container
                job = lifecycleScope.launch {
                    combine(TelemetryHub.telemetry, container.settings.settings) { t, s -> t to s }
                        .collect { (t, s) ->
                            telemetry = t
                            settings = s
                            maybeInvalidate()
                        }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel()
                job = null
            }
        })
    }

    private fun maybeInvalidate() {
        val now = SystemClock.elapsedRealtime()
        val values = rowValues()
        if (values == lastRendered || now - lastInvalidateMs < MIN_REFRESH_MS) return
        lastInvalidateMs = now
        invalidate()
    }

    /** The changing text of each row, in order. */
    private fun rowValues(): List<String> {
        val t = telemetry
        val u = settings.unit
        val trip = t.trip
        val speed = t.gpsSpeedKmh?.let { "${u.fromKmh(it).roundToInt()} ${u.label}" } ?: "-- ${u.label}"
        val speedNote = when {
            t.locationError != null -> "Location unavailable"
            !t.hasFix -> "Waiting for GPS"
            t.isStale -> "GPS signal lost"
            settings.speedWarningEnabled && (t.gpsSpeedKmh ?: 0.0) > settings.speedWarningKmh -> "Above your speed warning"
            t.gpsQuality == GpsQuality.POOR -> "Weak GPS"
            else -> "GPS speed, not the car's speedometer"
        }
        val tripLine = trip?.let {
            "${Format.distance(it.distanceM, u)} ${u.distanceLabel} · ${Format.duration(it.elapsedMs)}"
        } ?: "No trip running"
        val tripNote = trip?.let { "Moving ${Format.duration(it.movingMs)}" + if (it.autoStarted) " · auto" else "" }
            ?: if (settings.autoTrip) "Starts automatically when you drive" else "Tap Start trip"
        val avg = trip?.avgMovingKmh?.let { "${u.fromKmh(it).roundToInt()}" } ?: "--"
        val max = trip?.maxSpeedKmh?.takeIf { it > 0 }?.let { "${u.fromKmh(it).roundToInt()}" } ?: "--"
        val elevation = t.altitudeMeters?.let { Format.elevation(it, u) }?.let { "≈${it.first} ${it.second}" } ?: "n/a"
        val heading = t.bearingDegrees?.let { Format.compass(it) } ?: "--"
        return listOf(
            speed, speedNote,
            tripLine, tripNote,
            "avg $avg · max $max ${u.label}",
            "Heading $heading · elevation $elevation",
        )
    }

    override fun onGetTemplate(): Template {
        if (!LocationPermissions.hasPrecise(carContext)) return permissionTemplate(carContext)

        val v = rowValues()
        lastRendered = v
        val tripActive = telemetry.trip != null

        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Speed").addText(v[0]).addText(v[1]).build())
            .addRow(Row.Builder().setTitle("Trip").addText(v[2]).addText(v[3]).build())
            .addRow(Row.Builder().setTitle("Averages").addText(v[4]).addText(v[5]).build())
            .addAction(
                if (tripActive) {
                    Action.Builder()
                        .setTitle("End trip")
                        .setBackgroundColor(CarColor.RED)
                        .setOnClickListener {
                            DrivingService.send(carContext, DrivingService.ACTION_STOP_TRIP)
                            CarToast.makeText(carContext, "Trip saved", CarToast.LENGTH_SHORT).show()
                        }
                        .build()
                } else {
                    Action.Builder()
                        .setTitle("Start trip")
                        .setBackgroundColor(CarColor.GREEN)
                        .setOnClickListener { DrivingService.send(carContext, DrivingService.ACTION_START_TRIP) }
                        .build()
                },
            )
            .build()

        @Suppress("DEPRECATION")
        return PaneTemplate.Builder(pane)
            .setTitle(settings.vehicle.nickname.ifBlank { "Kia Carens" })
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private companion object {
        const val MIN_REFRESH_MS = 2_000L
    }
}

/** Shown when location hasn't been granted yet; that can only be done on the phone. */
class PermissionCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template = permissionTemplate(carContext)
}

private fun permissionTemplate(carContext: CarContext): Template {
    @Suppress("DEPRECATION")
    return MessageTemplate.Builder(
        "Open Carens Dashboard on your phone and allow precise location, then come back here.",
    )
        .setTitle("Location needed")
        .setHeaderAction(Action.APP_ICON)
        .addAction(
            Action.Builder()
                .setTitle("Open on phone")
                .setOnClickListener {
                    val intent = Intent(carContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { carContext.startActivity(intent) }
                    CarToast.makeText(carContext, "Check your phone", CarToast.LENGTH_LONG).show()
                }
                .build(),
        )
        .build()
}
