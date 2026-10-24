package dev.carens.dashboard.info

import dev.carens.dashboard.BuildConfig
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.distanceMeters
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class WeatherInfo(
    val tempC: Double,
    val feelsLikeC: Double?,
    val humidityPct: Int?,
    val windKmh: Double?,
    val code: Int,
    val isDay: Boolean,
    val fetchedAtMs: Long,
    val lat: Double,
    val lon: Double,
) {
    val description: String get() = WeatherCodes.describe(code)
    val emoji: String get() = WeatherCodes.emoji(code, isDay)
    val tempLabel: String get() = "${tempC.roundToInt()}°"
}

object WeatherCodes {
    /** WMO weather interpretation codes, as used by Open-Meteo. */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "—"
    }

    fun emoji(code: Int, isDay: Boolean): String = when (code) {
        0, 1 -> if (isDay) "☀️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}

/**
 * Current weather from Open-Meteo (free, no API key). Refreshes every
 * 15 minutes, or sooner after moving 5 km. Only the rounded position is sent.
 */
class WeatherRepository(
    private val scope: CoroutineScope,
    private val telemetry: StateFlow<DashboardTelemetry>,
) {
    private val _weather = MutableStateFlow<WeatherInfo?>(null)
    val weather: StateFlow<WeatherInfo?> = _weather.asStateFlow()

    private var lastAttemptMs = 0L

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        scope.launch {
            telemetry.sample(10_000).collectLatest { t ->
                val lat = t.latitude ?: return@collectLatest
                val lon = t.longitude ?: return@collectLatest
                val now = System.currentTimeMillis()
                val current = _weather.value
                val stale = current == null ||
                    now - current.fetchedAtMs > 15 * 60_000 ||
                    distanceMeters(current.lat, current.lon, lat, lon) > 5_000
                if (stale && now - lastAttemptMs > 60_000) {
                    lastAttemptMs = now
                    runCatching { fetch(lat, lon) }.getOrNull()?.let { _weather.value = it }
                }
            }
        }
    }

    private suspend fun fetch(lat: Double, lon: Double): WeatherInfo = withContext(Dispatchers.IO) {
        // ~1 km precision is plenty for weather and avoids sending the exact position.
        val la = String.format(Locale.US, "%.2f", lat)
        val lo = String.format(Locale.US, "%.2f", lon)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
            "&timezone=auto"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "CarensDashboard/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(conn.responseCode == 200) { "weather ${conn.responseCode}" }
            val c = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            WeatherInfo(
                tempC = c.getDouble("temperature_2m"),
                feelsLikeC = c.optDouble("apparent_temperature").takeUnless { it.isNaN() },
                humidityPct = c.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
                windKmh = c.optDouble("wind_speed_10m").takeUnless { it.isNaN() },
                code = c.optInt("weather_code", -1),
                isDay = c.optInt("is_day", 1) == 1,
                fetchedAtMs = System.currentTimeMillis(),
                lat = lat,
                lon = lon,
            )
        } finally {
            conn.disconnect()
        }
    }
}
