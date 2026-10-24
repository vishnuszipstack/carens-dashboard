package dev.carens.dashboard.nav

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.telemetry.distanceMeters
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Turn-by-turn guidance on top of [RouteService]. Follows the GPS position,
 * advances through the route's steps, speaks prompts, and reroutes when the
 * car leaves the route.
 */
class Navigator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val routes: RouteService,
) {
    sealed interface Preview {
        data object None : Preview
        data class Loading(val destination: Destination) : Preview
        data class Ready(val destination: Destination, val route: Route) : Preview
        data class Failed(val destination: Destination, val message: String) : Preview
    }

    private val _preview = MutableStateFlow<Preview>(Preview.None)
    val preview: StateFlow<Preview> = _preview.asStateFlow()

    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state.asStateFlow()

    var voiceEnabled = true
    var imperial = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var followJob: Job? = null
    private var offRouteCount = 0
    private var lastRerouteMs = 0L
    private var announced = mutableSetOf<String>()
    private var closestToNext = Double.MAX_VALUE
    private var arrivedAtMs = 0L

    /** Fetch a route to show before starting. */
    fun preview(destination: Destination) {
        val t = TelemetryHub.telemetry.value
        val from = t.latitude?.let { LatLon(it, t.longitude!!) }
        if (from == null) {
            _preview.value = Preview.Failed(destination, "Waiting for your GPS position")
            return
        }
        _preview.value = Preview.Loading(destination)
        scope.launch {
            _preview.value = try {
                Preview.Ready(destination, routes.route(from, destination.point, t.bearingDegrees))
            } catch (e: Exception) {
                Preview.Failed(destination, e.message ?: "Couldn't get a route")
            }
        }
    }

    fun clearPreview() {
        _preview.value = Preview.None
    }

    fun start(destination: Destination, route: Route) {
        _preview.value = Preview.None
        announced.clear()
        offRouteCount = 0
        closestToNext = Double.MAX_VALUE
        arrivedAtMs = 0L
        _state.value = buildState(destination, route, nextIndex = 1.coerceAtMost(route.steps.lastIndex), TelemetryHub.telemetry.value)
        TelemetryHub.navigating.value = true
        ensureTts()
        say("Starting route to ${destination.name}. ${route.steps.getOrNull(1)?.instruction ?: ""}")
        followJob?.cancel()
        followJob = scope.launch { TelemetryHub.telemetry.collect { onTelemetry(it) } }
    }

    fun stop() {
        followJob?.cancel()
        followJob = null
        _state.value = null
        TelemetryHub.navigating.value = false
        tts?.stop()
    }

    private fun onTelemetry(t: DashboardTelemetry) {
        val s = _state.value ?: return
        val lat = t.latitude ?: return
        val lon = t.longitude ?: return
        if (t.isStale) return
        val here = LatLon(lat, lon)
        val route = s.route

        // Arrival.
        val dest = route.points.lastOrNull() ?: s.destination.point
        if (distanceMeters(lat, lon, dest.lat, dest.lon) < 35) {
            if (!s.arrived) {
                say("You have arrived at ${s.destination.name}")
                arrivedAtMs = SystemClock.elapsedRealtime()
                _state.value = s.copy(arrived = true, distanceToNextM = 0.0, remainingM = 0.0, remainingS = 0.0)
            } else if (SystemClock.elapsedRealtime() - arrivedAtMs > 20_000) {
                stop()
            }
            return
        }

        // Off route? Require a few good fixes in a row before rerouting.
        val accurate = (t.gpsAccuracyMeters ?: 99f) < 30f
        val off = Geo.distanceToPolyline(here, route.points)
        if (accurate && off > 60) offRouteCount++ else if (off < 40) offRouteCount = 0
        if (offRouteCount >= 4 && !s.rerouting && SystemClock.elapsedRealtime() - lastRerouteMs > 20_000) {
            reroute(s, here, t.bearingDegrees)
            return
        }

        // Advance past maneuvers we've reached (or clearly passed).
        var idx = s.nextStepIndex
        var dNext = distanceTo(here, route.steps[idx].location)
        if (dNext < closestToNext) closestToNext = dNext
        val passed = closestToNext < 60 && dNext > closestToNext + 25
        if ((dNext < 18 || passed) && idx < route.steps.lastIndex) {
            idx++
            closestToNext = Double.MAX_VALUE
            dNext = distanceTo(here, route.steps[idx].location)
        }

        val ns = buildState(s.destination, route, idx, t, dNext).copy(rerouting = s.rerouting)
        _state.value = ns
        announce(ns, t.gpsSpeedKmh ?: 0.0)
    }

    private fun buildState(
        destination: Destination,
        route: Route,
        nextIndex: Int,
        t: DashboardTelemetry,
        distToNext: Double? = null,
    ): NavState {
        val here = t.latitude?.let { LatLon(it, t.longitude!!) }
        val next = route.steps[nextIndex]
        val dNext = distToNext ?: here?.let { distanceTo(it, next.location) } ?: 0.0
        // Steps after the next maneuver, plus the stretch to it.
        val after = route.steps.drop(nextIndex)
        val remainingM = dNext + after.sumOf { it.distanceM }
        val prev = route.steps[(nextIndex - 1).coerceAtLeast(0)]
        val prevSpeed = if (prev.distanceM > 0 && prev.durationS > 0) prev.distanceM / prev.durationS else 11.0
        val remainingS = dNext / prevSpeed + after.sumOf { it.durationS }
        return NavState(
            destination = destination,
            route = route,
            nextStepIndex = nextIndex,
            distanceToNextM = dNext,
            remainingM = remainingM,
            remainingS = remainingS,
            etaWallMs = System.currentTimeMillis() + (remainingS * 1000).toLong(),
        )
    }

    private fun reroute(s: NavState, here: LatLon, bearing: Float?) {
        lastRerouteMs = SystemClock.elapsedRealtime()
        offRouteCount = 0
        _state.value = s.copy(rerouting = true)
        say("Rerouting")
        scope.launch {
            try {
                val r = routes.route(here, s.destination.point, bearing)
                announced.clear()
                closestToNext = Double.MAX_VALUE
                _state.value = buildState(s.destination, r, 1.coerceAtMost(r.steps.lastIndex), TelemetryHub.telemetry.value)
            } catch (_: Exception) {
                _state.value = _state.value?.copy(rerouting = false)
            }
        }
    }

    /** Spoken prompts: early warning, prepare, and "now". Thresholds scale with speed. */
    private fun announce(s: NavState, speedKmh: Double) {
        val step = s.nextStep
        val d = s.distanceToNextM
        val key = "${s.route.hashCode()}:${s.nextStepIndex}"
        val far = if (speedKmh > 70) 1500.0 else 600.0
        val near = if (speedKmh > 70) 400.0 else 150.0
        val now = if (speedKmh > 50) 60.0 else 30.0
        when {
            d <= now && "$key:now" !in announced -> {
                announced += "$key:now"; announced += "$key:near"; announced += "$key:far"
                say(step.instruction)
            }
            d <= near && "$key:near" !in announced -> {
                announced += "$key:near"; announced += "$key:far"
                say("In ${navDistance(d, imperial)}, ${step.instruction.replaceFirstChar { it.lowercase() }}")
            }
            d <= far && d > near && "$key:far" !in announced && step.maneuver != Maneuver.STRAIGHT -> {
                announced += "$key:far"
                say("In ${navDistance(d, imperial)}, ${step.instruction.replaceFirstChar { it.lowercase() }}")
            }
        }
    }

    private fun distanceTo(a: LatLon, b: LatLon) = distanceMeters(a.lat, a.lon, b.lat, b.lon)

    private var pending: String? = null

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            val engine = tts ?: return@TextToSpeech
            if (ttsReady) {
                val preferred = Locale.getDefault().takeIf { it.language == "en" } ?: Locale.forLanguageTag("en-IN")
                engine.language = preferred
                engine.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                pending?.let { engine.speak(it, TextToSpeech.QUEUE_FLUSH, null, "nav") }
                pending = null
            }
        }
    }

    private fun say(text: String) {
        if (!voiceEnabled || text.isBlank()) return
        ensureTts()
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav") else pending = text
    }
}
