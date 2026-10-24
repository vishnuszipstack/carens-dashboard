package dev.carens.dashboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.carens.dashboard.AppContainer
import dev.carens.dashboard.DashApp
import dev.carens.dashboard.MainActivity
import dev.carens.dashboard.R
import dev.carens.dashboard.location.LocationPermissions
import dev.carens.dashboard.location.locationUpdates
import dev.carens.dashboard.location.toSample
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.telemetry.AutoTripDetector
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.GpsQuality
import dev.carens.dashboard.telemetry.GpsSample
import dev.carens.dashboard.telemetry.SpeedFilter
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.telemetry.TripSnapshot
import dev.carens.dashboard.telemetry.TripTracker
import dev.carens.dashboard.trips.TripEntity
import dev.carens.dashboard.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns GPS collection and trip recording. Runs as a visible foreground service
 * (type "location") so a trip keeps recording with the screen off or while
 * Google Maps is in front, without needing background-location permission.
 *
 * Lifetime:
 *  - Started whenever the dashboard is on screen.
 *  - With a trip running, it stays until the trip ends (manually or auto-stop).
 *  - With no trip and the dashboard hidden, it waits [IDLE_HIDDEN_SHUTDOWN_MS]
 *    for a drive to start (e.g. you opened Maps first), then stops.
 */
class DrivingService : Service() {

    private class ActiveTrip(val tracker: TripTracker, val auto: Boolean, val id: Deferred<Long>) {
        /** Serializes the periodic save against the final save so an old snapshot never reopens a trip. */
        val lock = Mutex()
        var finished = false // guarded by lock
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container: AppContainer get() = (application as DashApp).container

    private val filter = SpeedFilter()
    private val detector = AutoTripDetector()
    private var settings = DashSettings()

    private var lastSample: GpsSample? = null
    private var lastLocation: Location? = null
    private var displayKmh: Double? = null
    private var mslAltitudeM: Double? = null
    private var lastAltitudeLookupMs = 0L
    private var locationError: String? = null

    private var active: ActiveTrip? = null
    private var lastPersistMs = 0L
    private var lastNotifyMs = 0L
    private var hiddenSinceMs: Long? = null
    private var started = false
    private var stopping = false
    private lateinit var recovery: Deferred<Unit>

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!enterForeground()) {
            stopSelf()
            return
        }
        started = true

        recovery = container.appScope.async { container.trips.closeInterruptedTrips(MIN_TRIP_M) }

        scope.launch { container.settings.settings.collect { settings = it } }
        scope.launch {
            locationUpdates(this@DrivingService)
                .catch { e ->
                    locationError = e.message ?: "Location unavailable"
                    publish()
                }
                .collect { onLocation(it) }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                onTick()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) return START_NOT_STICKY
        enterForeground() // satisfies every startForegroundService() call
        val now = SystemClock.elapsedRealtime()
        when (intent?.action) {
            ACTION_START_TRIP -> startTrip(auto = false, startElapsedMs = now)
            ACTION_STOP_TRIP -> stopTrip(now)
            ACTION_EXIT -> {
                stopTrip(now)
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active?.let { finalizeTrip(it, SystemClock.elapsedRealtime()) }
        active = null
        stopping = true
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        TelemetryHub.clear()
        super.onDestroy()
    }

    // --- GPS ---------------------------------------------------------------

    private fun onLocation(loc: Location) {
        locationError = null
        val s = loc.toSample()
        lastSample = s
        lastLocation = loc
        if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) mslAltitudeM = loc.mslAltitudeMeters

        val rawKmh = s.speedMps?.let { it * 3.6 }
        val reliable = rawKmh != null && GpsQuality.fromAccuracy(s.accuracyM) != GpsQuality.POOR
        displayKmh = rawKmh?.let { filter.update(it) }

        active?.tracker?.onSample(s)

        val decision = detector.onSample(s.timeMs, rawKmh, reliable, active != null)
        if (settings.autoTrip && decision is AutoTripDetector.Decision.Start) {
            startTrip(auto = true, startElapsedMs = decision.sinceElapsedMs)
        }
        publish()
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val s = lastSample
        if (s == null || now - s.timeMs > STALE_MS) {
            filter.reset()
            displayKmh = null
        }

        if (settings.autoTrip) {
            val d = detector.onTick(now, active != null)
            if (d is AutoTripDetector.Decision.Stop) stopTrip(d.endElapsedMs)
        }

        active?.let { if (now - lastPersistMs >= PERSIST_EVERY_MS) persist(it, now) }

        if (Build.VERSION.SDK_INT >= 34) lookUpMslAltitude(now)

        // Idle shutdown when nobody is looking and nothing is being recorded.
        if (TelemetryHub.anyUiVisible || active != null) {
            hiddenSinceMs = null
        } else {
            val since = hiddenSinceMs ?: now.also { hiddenSinceMs = it }
            if (now - since >= IDLE_HIDDEN_SHUTDOWN_MS) {
                shutdown()
                return
            }
        }

        publish(now)
        if (now - lastNotifyMs >= NOTIFY_EVERY_MS) updateNotification()
    }

    /** GNSS altitude is height above the ellipsoid (off by ~-90 m around Kerala); convert to sea level. */
    @RequiresApi(34)
    private fun lookUpMslAltitude(now: Long) {
        val loc = lastLocation ?: return
        if (now - lastAltitudeLookupMs < 10_000 || !loc.hasAltitude() || loc.hasMslAltitude()) return
        lastAltitudeLookupMs = now
        val copy = Location(loc)
        scope.launch {
            val msl = withContext(Dispatchers.IO) {
                runCatching {
                    AltitudeConverter().addMslAltitudeToLocation(applicationContext, copy)
                    if (copy.hasMslAltitude()) copy.mslAltitudeMeters else null
                }.getOrNull()
            }
            if (msl != null) mslAltitudeM = msl
        }
    }

    // --- Trips -------------------------------------------------------------

    private fun startTrip(auto: Boolean, startElapsedMs: Long) {
        if (active != null) return
        val now = SystemClock.elapsedRealtime()
        val startWall = System.currentTimeMillis() - (now - startElapsedMs)
        val tracker = TripTracker(startElapsedMs, startWall)
        lastSample?.takeIf { it.timeMs >= startElapsedMs }?.let(tracker::onSample)

        val entity = TripEntity(
            startedAtWallMs = startWall,
            lastUpdatedWallMs = System.currentTimeMillis(),
            autoStarted = auto,
        )
        val id = container.appScope.async {
            recovery.await()
            container.trips.insert(entity)
        }
        active = ActiveTrip(tracker, auto, id)
        detector.onTripStarted(now)
        lastPersistMs = now
        updateNotification()
        publish(now)
    }

    private fun stopTrip(endElapsedMs: Long) {
        val trip = active ?: return
        active = null
        detector.onTripEnded()
        finalizeTrip(trip, endElapsedMs)
        updateNotification()
        publish()
        if (!TelemetryHub.anyUiVisible) shutdown()
    }

    private fun finalizeTrip(trip: ActiveTrip, endElapsedMs: Long) {
        val entity = trip.toEntity(endElapsedMs, ended = true)
        container.appScope.launch {
            val id = trip.id.await()
            trip.lock.withLock {
                trip.finished = true
                if (entity.distanceM < MIN_TRIP_M) container.trips.delete(id)
                else container.trips.update(entity.copy(id = id))
            }
        }
    }

    private fun persist(trip: ActiveTrip, now: Long) {
        lastPersistMs = now
        val entity = trip.toEntity(now, ended = false)
        container.appScope.launch {
            val id = trip.id.await()
            trip.lock.withLock {
                if (!trip.finished) container.trips.update(entity.copy(id = id))
            }
        }
    }

    private fun ActiveTrip.toEntity(atElapsedMs: Long, ended: Boolean): TripEntity {
        val t = tracker
        val duration = t.elapsedMs(atElapsedMs)
        val endWall = t.startWallMs + duration
        return TripEntity(
            startedAtWallMs = t.startWallMs,
            endedAtWallMs = if (ended) endWall else null,
            lastUpdatedWallMs = endWall,
            distanceM = t.distanceM,
            durationMs = duration,
            movingMs = t.movingMs,
            maxSpeedKmh = t.maxSpeedKmh,
            gpsGapMs = t.gapMs,
            autoStarted = auto,
        )
    }

    // --- Publishing ----------------------------------------------------------

    private fun publish(now: Long = SystemClock.elapsedRealtime()) {
        val s = lastSample
        val fresh = s?.takeIf { now - it.timeMs <= STALE_MS }
        val quality = if (fresh == null) GpsQuality.NONE else GpsQuality.fromAccuracy(fresh.accuracyM)
        val speed = if (fresh != null) displayKmh else null
        TelemetryHub.publish(
            DashboardTelemetry(
                timestampMs = System.currentTimeMillis(),
                gpsSpeedKmh = speed,
                gpsAccuracyMeters = fresh?.accuracyM,
                latitude = s?.lat,
                longitude = s?.lon,
                // Bearing is meaningless when nearly stationary.
                bearingDegrees = fresh?.bearingDeg?.takeIf { (speed ?: 0.0) >= 5.0 },
                altitudeMeters = mslAltitudeM,
                gpsQuality = quality,
                isGpsReliable = quality == GpsQuality.GOOD || quality == GpsQuality.FAIR,
                hasFix = s != null,
                isStale = fresh == null,
                trip = active?.let { a ->
                    val t = a.tracker
                    TripSnapshot(
                        startedAtWallMs = t.startWallMs,
                        distanceM = t.distanceM,
                        elapsedMs = t.elapsedMs(now),
                        movingMs = t.movingMs,
                        avgMovingKmh = t.avgMovingKmh,
                        maxSpeedKmh = t.maxSpeedKmh,
                        gpsGapMs = t.gapMs,
                        autoStarted = a.auto,
                    )
                },
                locationError = locationError,
                serviceRunning = true,
            ),
        )
    }

    // --- Foreground / notification -----------------------------------------

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (e: Exception) {
        // Missing permission or a background-start restriction. Callers check permission first,
        // so this should not happen; if it does, Android will still report the failed start.
        false
    }

    private fun shutdown() {
        stopping = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Driving dashboard", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the dashboard is using GPS or recording a trip"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification() {
        lastNotifyMs = SystemClock.elapsedRealtime()
        if (!started || stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val trip = active
        val unit = settings.unit
        val title = if (trip == null) "Dashboard using GPS" else "Recording trip"
        val text = if (trip == null) {
            if (settings.autoTrip) "A trip will start when you drive" else "No trip running"
        } else {
            val t = trip.tracker
            "${Format.distance(t.distanceM, unit)} ${unit.distanceLabel} · " +
                Format.duration(t.elapsedMs(SystemClock.elapsedRealtime()))
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
        if (trip != null) builder.addAction(0, "End trip", actionIntent(ACTION_STOP_TRIP, 1))
        builder.addAction(0, "Exit", actionIntent(ACTION_EXIT, 2))
        return builder.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, DrivingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START_TRIP = "dev.carens.dashboard.action.START_TRIP"
        const val ACTION_STOP_TRIP = "dev.carens.dashboard.action.STOP_TRIP"
        const val ACTION_EXIT = "dev.carens.dashboard.action.EXIT"

        private const val CHANNEL_ID = "driving"
        private const val NOTIFICATION_ID = 42
        private const val STALE_MS = 4_000L
        private const val PERSIST_EVERY_MS = 30_000L
        private const val NOTIFY_EVERY_MS = 15_000L
        private const val IDLE_HIDDEN_SHUTDOWN_MS = 10 * 60_000L

        /** Trips shorter than this are treated as accidental and discarded. */
        const val MIN_TRIP_M = 50.0

        fun start(context: Context) = send(context, null)

        fun send(context: Context, action: String?) {
            if (!LocationPermissions.hasPrecise(context)) return
            val intent = Intent(context, DrivingService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
