package dev.carens.dashboard.car

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.format.DateFormat
import androidx.media.MediaBrowserServiceCompat
import dev.carens.dashboard.DashApp
import dev.carens.dashboard.R
import dev.carens.dashboard.info.NowPlaying
import dev.carens.dashboard.info.PlaceInfo
import dev.carens.dashboard.info.WeatherInfo
import dev.carens.dashboard.location.LocationPermissions
import dev.carens.dashboard.service.DrivingService
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.telemetry.GpsQuality
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.telemetry.distanceMeters
import dev.carens.dashboard.trips.avgMovingKmh
import dev.carens.dashboard.ui.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The car dashboard, presented as a media app because Android Auto still shows
 * sideloaded media apps (it hides sideloaded template apps).
 *
 *  - Now-playing picture: speed gauge, mini-map, clock, weather, trip, place, music.
 *  - Title/subtitle: speed + weather, trip.
 *  - Play/pause/skip control whatever music app is playing (needs notification access).
 *  - Custom button: Start trip / End trip.
 *  - Browse list: a full-width info panel plus recent trips.
 *
 * No audio is ever played and audio focus is never requested.
 */
class DashMediaService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat
    private val container get() = (application as DashApp).container

    private var telemetry = TelemetryHub.telemetry.value
    private var settings = DashSettings()
    private var weather: WeatherInfo? = null
    private var place: PlaceInfo? = null
    private var music: NowPlaying? = null
    private var nav: dev.carens.dashboard.nav.NavState? = null
    private var serviceStarted = false

    /**
     * Report "playing" only after the driver opens the dashboard in Android Auto,
     * so this silent session doesn't take over the now-playing widget on its own.
     */
    private var shown = false

    private var art: Bitmap? = null
    private var lastArtKey: String? = null
    private var lastArtMs = 0L
    private var lastTextKey: String? = null
    private var lastListKey: String? = null
    private var lastListMs = 0L

    private var mapBitmap: Bitmap? = null
    private var mapLat = 0.0
    private var mapLon = 0.0
    private var mapJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "CarensDashboard").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = session.sessionToken

        TelemetryHub.carVisible.value = true
        serviceStarted = DrivingService.start(this)
        container.music.refresh()

        scope.launch {
            combine(
                TelemetryHub.telemetry,
                container.settings.settings,
                container.weather.weather,
                container.place.place,
                container.music.nowPlaying,
            ) { t, s, w, p, m -> Snapshot(t, s, w, p, m) }
                .collect { snap ->
                    telemetry = snap.t
                    settings = snap.s
                    weather = snap.w
                    place = snap.p
                    music = snap.m
                    if (!serviceStarted && LocationPermissions.hasPrecise(this@DashMediaService)) {
                        serviceStarted = DrivingService.start(this@DashMediaService)
                    }
                    maybeUpdateMap()
                    publish()
                }
        }
        scope.launch {
            container.navigator.state.collect {
                nav = it
                publish()
            }
        }
        // The clock and "GPS lost" state change even when nothing is emitted.
        scope.launch {
            while (isActive) {
                delay(15_000)
                publish()
            }
        }
        publish(force = true)
    }

    override fun onDestroy() {
        TelemetryHub.carVisible.value = false
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private data class Snapshot(
        val t: dev.carens.dashboard.telemetry.DashboardTelemetry,
        val s: DashSettings,
        val w: WeatherInfo?,
        val p: PlaceInfo?,
        val m: NowPlaying?,
    )

    // --- Mini-map --------------------------------------------------------------

    private fun maybeUpdateMap() {
        val lat = telemetry.latitude ?: return
        val lon = telemetry.longitude ?: return
        if (mapJob?.isActive == true) return
        val moved = mapBitmap == null || distanceMeters(mapLat, mapLon, lat, lon) > 25
        if (!moved) return
        mapJob = scope.launch {
            val bmp = container.mapTiles.render(lat, lon, zoom = 16, size = 512, dark = true)
            if (bmp != null) {
                mapBitmap = bmp
                mapLat = lat
                mapLon = lon
                lastArtKey = null // force a redraw with the new map
                publish()
            }
            delay(4_000) // at most one map refresh every few seconds
        }
    }

    // --- Browsing ------------------------------------------------------------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (clientPackageName !in ALLOWED_CLIENTS && clientPackageName != packageName) return null
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        when (parentId) {
            ROOT_ID -> result.sendResult(
                (listOf(item(LIVE_ID, "▶  Live dashboard", "Speed, map, weather and music", playable = true)) +
                    infoRows().map { (id, title, sub) -> item(id, title, sub, playable = true) } +
                    item(TRIPS_ID, "Recent trips", "Your last 20 trips", playable = false)).toMutableList(),
            )
            TRIPS_ID -> {
                result.detach()
                scope.launch {
                    val u = settings.unit
                    val day = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
                    val trips = withContext(Dispatchers.IO) { container.trips.finishedTrips.first().take(20) }
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

    /** Full-width rows for the browse view: the most "full screen" a media app can get. */
    private fun infoRows(): List<Triple<String, String, String>> {
        val t = telemetry
        val u = settings.unit
        val rows = mutableListOf<Triple<String, String, String>>()

        val speed = t.gpsSpeedKmh?.let { "${u.fromKmh(it).roundToInt()} ${u.label}" } ?: "-- ${u.label}"
        val heading = t.bearingDegrees?.let { " · heading ${Format.compass(it)}" } ?: ""
        val gps = when {
            !t.hasFix -> "Waiting for GPS"
            t.isStale -> "GPS signal lost"
            else -> "GPS ±${t.gpsAccuracyMeters?.roundToInt() ?: "?"} m" +
                (t.altitudeMeters?.let { a -> " · elevation ≈${Format.elevation(a, u).let { "${it.first} ${it.second}" }}" } ?: "")
        }
        rows += Triple("info:speed", "🚗  $speed$heading", gps)

        val trip = t.trip
        rows += if (trip == null) {
            Triple("info:trip", "No trip running", if (settings.autoTrip) "Starts automatically when you drive" else "Use Start trip")
        } else {
            val avg = trip.avgMovingKmh?.let { "${u.fromKmh(it).roundToInt()}" } ?: "--"
            val max = trip.maxSpeedKmh.takeIf { it > 0 }?.let { "${u.fromKmh(it).roundToInt()}" } ?: "--"
            Triple(
                "info:trip",
                "🛣️  ${Format.distance(trip.distanceM, u)} ${u.distanceLabel} · ${Format.duration(trip.elapsedMs)}",
                "Moving ${Format.duration(trip.movingMs)} · avg $avg · max $max ${u.label}",
            )
        }

        weather?.let { w ->
            val details = buildList {
                w.feelsLikeC?.let { add("feels ${it.roundToInt()}°") }
                w.windKmh?.let { add("wind ${it.roundToInt()} km/h") }
                w.humidityPct?.let { add("humidity $it%") }
            }.joinToString(" · ")
            rows += Triple("info:weather", "${w.emoji}  ${w.tempLabel} ${w.description}", details)
        }
        place?.let { p ->
            rows += Triple(
                "info:place",
                "📍  ${p.label}",
                String.format(Locale.US, "%.4f, %.4f", t.latitude ?: p.lat, t.longitude ?: p.lon),
            )
        }
        music?.let { m ->
            rows += Triple("info:music", "♪  ${m.title}", listOfNotNull(m.artist, m.appName).joinToString(" · "))
        }
        return rows
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
        override fun onPlay() {
            // The first play just opens the dashboard; after that it controls the music app.
            if (!shown) show(true) else container.music.play()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = show(true)

        override fun onPause() {
            if (music != null) container.music.pause() else show(false)
        }

        override fun onStop() = show(false)
        override fun onSkipToNext() {
            container.music.next()
        }

        override fun onSkipToPrevious() {
            container.music.previous()
        }

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
        val m = music

        val trip = t.trip
        val content = CarView.build(this, t, s, weather, place, m, nav, mapBitmap, hasPermission)
        val title = content.title
        val subtitle = content.subtitle
        val third = content.third
        val artKey = content.artKey

        // Redraw the picture only when something on it changes, at most once a second.
        val now = SystemClock.elapsedRealtime()
        if (art == null || (artKey != lastArtKey && (force || now - lastArtMs >= 1_000))) {
            lastArtKey = artKey
            lastArtMs = now
            val full = GaugeArt.render(content.model)
            // ~320 KB keeps each update well under the binder transaction limit.
            art = Bitmap.createScaledBitmap(full, ART_PX, ART_PX, true).copy(Bitmap.Config.RGB_565, false)
            full.recycle()
        }

        val textKey = "$title|$subtitle|$third|$artKey|${trip != null}|$shown|${m?.isPlaying}"
        if (force || textKey != lastTextKey) {
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
            session.setPlaybackState(playbackState(hasPermission, trip != null, m))
        }

        // Refresh the browse list at most every 5 s.
        val listKey = infoRows().joinToString("|") { it.second + it.third }
        if (listKey != lastListKey && now - lastListMs >= 5_000) {
            lastListKey = listKey
            lastListMs = now
            notifyChildrenChanged(ROOT_ID)
        }
    }

    private fun playbackState(hasPermission: Boolean, tripActive: Boolean, m: NowPlaying?): PlaybackStateCompat {
        val b = PlaybackStateCompat.Builder()
        if (!hasPermission) {
            b.setState(PlaybackStateCompat.STATE_ERROR, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
            b.setErrorMessage(
                PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED,
                "Open Carens Dashboard on your phone and allow precise location.",
            )
            return b.build()
        }
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        if (m != null) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        val state = when {
            !shown -> PlaybackStateCompat.STATE_PAUSED
            m != null -> if (m.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_PLAYING
        }
        b.setActions(actions)
        b.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
        b.addCustomAction(
            if (!tripActive) {
                PlaybackStateCompat.CustomAction.Builder(ACTION_START, "Start trip", R.drawable.ic_action_start).build()
            } else {
                PlaybackStateCompat.CustomAction.Builder(ACTION_END, "End trip", R.drawable.ic_action_stop).build()
            },
        )
        return b.build()
    }

    private companion object {
        const val ART_PX = 400

        const val ROOT_ID = "root"
        const val LIVE_ID = "live"
        const val TRIPS_ID = "trips"
        const val ACTION_START = "dev.carens.dashboard.media.START_TRIP"
        const val ACTION_END = "dev.carens.dashboard.media.END_TRIP"

        // Android Auto content-style hints (from the media-app docs).
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_LIST = 1

        /** Android Auto, the phone's own media UI, and Google Assistant. */
        val ALLOWED_CLIENTS = setOf(
            "com.google.android.projection.gearhead",
            "com.android.systemui",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
        )
    }
}
