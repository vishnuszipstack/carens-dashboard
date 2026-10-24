package dev.carens.dashboard.car

import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import dev.carens.dashboard.DashApp
import dev.carens.dashboard.R
import dev.carens.dashboard.location.LocationPermissions
import dev.carens.dashboard.service.DrivingService
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.GpsQuality
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.trips.avgMovingKmh
import dev.carens.dashboard.ui.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android Auto shows sideloaded *media* apps when "Unknown sources" is on,
 * but no longer shows sideloaded template apps. So on the car the dashboard
 * is presented as a media app:
 *
 *  - Now-playing artwork: a drawn speedometer.
 *  - Title: speed. Subtitle: trip distance and time. Album line: avg/max.
 *  - Custom buttons: Start trip / End trip.
 *  - Browse list: "Live dashboard" and recent trips.
 *
 * No audio is ever played and audio focus is never requested.
 */
class DashMediaService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat

    private var telemetry = TelemetryHub.telemetry.value
    private var settings = DashSettings()
    private var serviceStarted = false

    /**
     * Only report "playing" after the driver opens the dashboard in Android Auto.
     * Otherwise this silent session would take over Android Auto's now-playing
     * widget from real music apps.
     */
    private var shown = false

    private var lastArtKey: String? = null
    private var lastArtMs = 0L
    private var art = GaugeArt.render(null, "km/h", 0f, false, null)
    private var lastTextKey: String? = null

    private val container get() = (application as DashApp).container

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "CarensDashboard").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = session.sessionToken

        TelemetryHub.carVisible.value = true
        serviceStarted = DrivingService.start(this)

        scope.launch {
            combine(TelemetryHub.telemetry, container.settings.settings) { t, s -> t to s }
                .collect { (t, s) ->
                    telemetry = t
                    settings = s
                    if (!serviceStarted && LocationPermissions.hasPrecise(this@DashMediaService)) {
                        serviceStarted = DrivingService.start(this@DashMediaService)
                    }
                    publish()
                }
        }
        publish()
    }

    override fun onDestroy() {
        TelemetryHub.carVisible.value = false
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    // --- Browsing ------------------------------------------------------------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? =
        if (clientPackageName in ALLOWED_CLIENTS || clientPackageName == packageName) {
            BrowserRoot(ROOT_ID, null)
        } else {
            null // nothing else gets to read location-derived data
        }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        when (parentId) {
            ROOT_ID -> result.sendResult(
                mutableListOf(
                    item(LIVE_ID, "Live dashboard", "Speed and current trip", playable = true),
                    item(TRIPS_ID, "Recent trips", "Last 20 trips", playable = false),
                ),
            )
            TRIPS_ID -> {
                result.detach()
                scope.launch {
                    val u = settings.unit
                    val day = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
                    val trips = container.trips.finishedTrips.first().take(20)
                    val items = trips.map { t ->
                        val avg = t.avgMovingKmh()?.let { " · avg ${u.fromKmh(it).roundToInt()} ${u.label}" } ?: ""
                        item(
                            "trip:${t.id}",
                            "${Format.distance(t.distanceM, u)} ${u.distanceLabel} · ${Format.duration(t.durationMs)}",
                            day.format(Date(t.startedAtWallMs)) + avg,
                            playable = true,
                        )
                    }.ifEmpty { listOf(item("trip:none", "No trips yet", "Trips appear here after you drive", playable = true)) }
                    result.sendResult(items.toMutableList())
                }
            }
            else -> result.sendResult(mutableListOf())
        }
    }

    private fun item(id: String, title: String, subtitle: String, playable: Boolean): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        val flag = if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        return MediaBrowserCompat.MediaItem(desc, flag)
    }

    // --- Controls --------------------------------------------------------------

    private val callback = object : MediaSessionCompat.Callback() {
        // Selecting any item just opens the now-playing dashboard; nothing plays.
        override fun onPlay() = show(true)
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = show(true)
        override fun onPause() = show(false)
        override fun onStop() = show(false)

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACTION_START -> DrivingService.send(this@DashMediaService, DrivingService.ACTION_START_TRIP)
                ACTION_END -> DrivingService.send(this@DashMediaService, DrivingService.ACTION_STOP_TRIP)
            }
        }
    }

    private fun show(visible: Boolean) {
        shown = visible
        publish(force = true)
    }

    // --- Publishing ------------------------------------------------------------

    private fun publish(force: Boolean = false) {
        val t = telemetry
        val s = settings
        val u = s.unit
        val hasPermission = LocationPermissions.hasPrecise(this)

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
        val title = speed?.let { "$it ${u.label}" } ?: "-- ${u.label}"
        val subtitle = trip?.let {
            "Trip ${Format.distance(it.distanceM, u)} ${u.distanceLabel} · ${Format.duration(it.elapsedMs)}"
        } ?: if (s.autoTrip) "No trip · starts when you drive" else "No trip running"
        val third = trip?.let {
            val avg = it.avgMovingKmh?.let { a -> u.fromKmh(a).roundToInt().toString() } ?: "--"
            val max = it.maxSpeedKmh.takeIf { m -> m > 0 }?.let { m -> u.fromKmh(m).roundToInt().toString() } ?: "--"
            "avg $avg · max $max ${u.label}"
        } ?: status ?: "GPS speed"

        // Redraw the gauge only when what it shows changes, at most once a second.
        val now = SystemClock.elapsedRealtime()
        val artKey = "$speed|$over|$status|${u.name}"
        if (artKey != lastArtKey && (force || now - lastArtMs >= 1_000)) {
            lastArtKey = artKey
            lastArtMs = now
            val fraction = ((speed ?: 0) / u.gaugeMax).toFloat()
            art = GaugeArt.render(speed, u.label, fraction, over, status)
        }

        val textKey = "$title|$subtitle|$third|$artKey|${trip != null}|$shown"
        if (!force && textKey == lastTextKey) return
        lastTextKey = textKey

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, LIVE_ID)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, third)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, third)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                .build(),
        )

        val stateBuilder = PlaybackStateCompat.Builder()
            // Play opens the dashboard, pause hands Android Auto's now-playing back to music. No skip/seek.
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
            )
        if (!hasPermission) {
            stateBuilder.setState(PlaybackStateCompat.STATE_ERROR, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
            stateBuilder.setErrorMessage(
                PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                "Open Carens Dashboard on your phone and allow precise location.",
            )
        } else {
            stateBuilder.setState(
                if (shown) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                0f,
            )
            stateBuilder.addCustomAction(
                if (trip == null) {
                    PlaybackStateCompat.CustomAction.Builder(ACTION_START, "Start trip", R.drawable.ic_action_start).build()
                } else {
                    PlaybackStateCompat.CustomAction.Builder(ACTION_END, "End trip", R.drawable.ic_action_stop).build()
                },
            )
        }
        session.setPlaybackState(stateBuilder.build())
    }

    private companion object {
        const val ROOT_ID = "root"
        const val LIVE_ID = "live"
        const val TRIPS_ID = "trips"
        const val ACTION_START = "dev.carens.dashboard.media.START_TRIP"
        const val ACTION_END = "dev.carens.dashboard.media.END_TRIP"

        /** Android Auto, the phone's own media UI, and Google Assistant/Auto helpers. */
        val ALLOWED_CLIENTS = setOf(
            "com.google.android.projection.gearhead",
            "com.android.systemui",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
        )
    }
}
