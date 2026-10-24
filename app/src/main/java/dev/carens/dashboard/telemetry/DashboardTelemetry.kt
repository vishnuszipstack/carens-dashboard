package dev.carens.dashboard.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DataSource { PHONE_GPS }

/**
 * A reading from the vehicle itself. Every value carries where it came from
 * and when, so the UI can never pass off a stale or guessed number as live.
 */
data class VehicleReading<T>(
    val value: T,
    val unit: String,
    val source: String,
    val timestampMs: Long,
)

/**
 * Slots for ECU data. In the phone-only build there is no legitimate source,
 * so these are always null and the UI shows "Not available".
 */
data class VehicleReadings(
    val rpm: VehicleReading<Int>? = null,
    val coolantTempC: VehicleReading<Double>? = null,
    val oilTempC: VehicleReading<Double>? = null,
    val intakeAirTempC: VehicleReading<Double>? = null,
    val engineLoadPct: VehicleReading<Double>? = null,
    val batteryVoltage: VehicleReading<Double>? = null,
    val fuelLevelPct: VehicleReading<Double>? = null,
)

data class TripSnapshot(
    val startedAtWallMs: Long,
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val avgMovingKmh: Double?,
    val maxSpeedKmh: Double,
    val gpsGapMs: Long,
    val autoStarted: Boolean,
)

data class DashboardTelemetry(
    val timestampMs: Long = 0,
    /** Smoothed GPS speed. Null when there is no fresh fix — never a guess. */
    val gpsSpeedKmh: Double? = null,
    val gpsAccuracyMeters: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val bearingDegrees: Float? = null,
    /** Mean-sea-level altitude when the platform can provide it, else null. */
    val altitudeMeters: Double? = null,
    val gpsQuality: GpsQuality = GpsQuality.NONE,
    val isGpsReliable: Boolean = false,
    val hasFix: Boolean = false,
    val isStale: Boolean = true,
    val trip: TripSnapshot? = null,
    /** Not readable from Google Maps; reserved for a future in-app navigation source. */
    val navigationEtaWallMs: Long? = null,
    val navigationDistanceRemainingM: Double? = null,
    val dataSource: DataSource = DataSource.PHONE_GPS,
    val vehicle: VehicleReadings = VehicleReadings(),
    val locationError: String? = null,
    val serviceRunning: Boolean = false,
)

/** In-process bus between the driving service and whatever UI is showing. */
object TelemetryHub {
    private val _telemetry = MutableStateFlow(DashboardTelemetry())
    val telemetry: StateFlow<DashboardTelemetry> = _telemetry.asStateFlow()

    /** True while the phone dashboard activity is on screen. */
    val uiVisible = MutableStateFlow(false)

    /** True while the Android Auto screen is showing on the car display. */
    val carVisible = MutableStateFlow(false)

    /** Most recent 0–60 / 0–100 km/h times from this session (target km/h → ms). */
    val lastAccel = MutableStateFlow<Map<Int, Long>>(emptyMap())

    /** True while turn-by-turn navigation is running; keeps GPS alive like a visible screen. */
    val navigating = MutableStateFlow(false)

    val anyUiVisible: Boolean get() = uiVisible.value || carVisible.value || navigating.value

    fun publish(t: DashboardTelemetry) {
        _telemetry.value = t
    }

    fun clear() {
        _telemetry.value = DashboardTelemetry()
    }
}
