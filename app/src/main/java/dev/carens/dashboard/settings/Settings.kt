package dev.carens.dashboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class SpeedUnit(
    val label: String,
    val perKmh: Double,
    val distanceLabel: String,
    val metersPerDistanceUnit: Double,
) {
    KMH("km/h", 1.0, "km", 1000.0),
    MPH("mph", 0.621371, "mi", 1609.344);

    fun fromKmh(kmh: Double): Double = kmh * perKmh
    fun toKmh(value: Double): Double = value / perKmh

    /** Top of the speedometer arc in this unit. */
    val gaugeMax: Double get() = if (this == KMH) 180.0 else 120.0
}

enum class ThemeMode { AUTO, DARK, LIGHT }

data class VehicleProfile(
    val nickname: String = "",
    val modelYear: String = "",
    val fuelType: String = "Petrol",
    val transmission: String = "Manual",
    val engineVariant: String = "",
)

data class DashSettings(
    val unit: SpeedUnit = SpeedUnit.KMH,
    val speedWarningEnabled: Boolean = false,
    /** Always stored in km/h regardless of display unit. */
    val speedWarningKmh: Int = 80,
    val autoTrip: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val keepScreenOn: Boolean = true,
    val showEcuSlots: Boolean = false,
    val vehicle: VehicleProfile = VehicleProfile(),
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val unit = stringPreferencesKey("unit")
        val warnEnabled = booleanPreferencesKey("warn_enabled")
        val warnKmh = intPreferencesKey("warn_kmh")
        val autoTrip = booleanPreferencesKey("auto_trip")
        val theme = stringPreferencesKey("theme")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val showEcu = booleanPreferencesKey("show_ecu")
        val nickname = stringPreferencesKey("vehicle_nickname")
        val modelYear = stringPreferencesKey("vehicle_model_year")
        val fuel = stringPreferencesKey("vehicle_fuel")
        val transmission = stringPreferencesKey("vehicle_transmission")
        val engine = stringPreferencesKey("vehicle_engine")
    }

    val settings: Flow<DashSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { read(it) }

    suspend fun update(transform: (DashSettings) -> DashSettings) {
        context.dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): DashSettings {
        val d = DashSettings()
        return DashSettings(
            unit = p[Keys.unit]?.let { runCatching { SpeedUnit.valueOf(it) }.getOrNull() } ?: d.unit,
            speedWarningEnabled = p[Keys.warnEnabled] ?: d.speedWarningEnabled,
            speedWarningKmh = p[Keys.warnKmh] ?: d.speedWarningKmh,
            autoTrip = p[Keys.autoTrip] ?: d.autoTrip,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            showEcuSlots = p[Keys.showEcu] ?: d.showEcuSlots,
            vehicle = VehicleProfile(
                nickname = p[Keys.nickname] ?: d.vehicle.nickname,
                modelYear = p[Keys.modelYear] ?: d.vehicle.modelYear,
                fuelType = p[Keys.fuel] ?: d.vehicle.fuelType,
                transmission = p[Keys.transmission] ?: d.vehicle.transmission,
                engineVariant = p[Keys.engine] ?: d.vehicle.engineVariant,
            ),
        )
    }

    private fun write(p: MutablePreferences, s: DashSettings) {
        p[Keys.unit] = s.unit.name
        p[Keys.warnEnabled] = s.speedWarningEnabled
        p[Keys.warnKmh] = s.speedWarningKmh
        p[Keys.autoTrip] = s.autoTrip
        p[Keys.theme] = s.themeMode.name
        p[Keys.keepScreenOn] = s.keepScreenOn
        p[Keys.showEcu] = s.showEcuSlots
        p[Keys.nickname] = s.vehicle.nickname
        p[Keys.modelYear] = s.vehicle.modelYear
        p[Keys.fuel] = s.vehicle.fuelType
        p[Keys.transmission] = s.vehicle.transmission
        p[Keys.engine] = s.vehicle.engineVariant
    }
}
