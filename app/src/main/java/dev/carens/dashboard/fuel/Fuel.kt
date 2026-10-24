package dev.carens.dashboard.fuel

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** One fill-up, as entered by the driver from the car's odometer and the pump receipt. */
data class FuelEntry(
    val id: Long,
    val timeMs: Long,
    val odometerKm: Double,
    val litres: Double,
    val totalCost: Double,
    val fullTank: Boolean,
) {
    val pricePerLitre: Double get() = if (litres > 0) totalCost / litres else 0.0
}

/** Mileage for one full-to-full interval. */
data class MileageInterval(val endEntryId: Long, val km: Double, val litres: Double, val cost: Double) {
    val kmPerLitre: Double get() = if (litres > 0) km / litres else 0.0
    val costPerKm: Double get() = if (km > 0) cost / km else 0.0
}

data class FuelSummary(
    val intervals: List<MileageInterval>,
    val averageKmPerLitre: Double?,
    val lastKmPerLitre: Double?,
    val averageCostPerKm: Double?,
    val totalSpent: Double,
    val totalLitres: Double,
)

object FuelMath {
    /**
     * Standard full-to-full method: fuel added since the previous full tank
     * (including partial fills in between) was used to drive the distance
     * between the two full-tank odometer readings.
     */
    fun summarize(entries: List<FuelEntry>): FuelSummary {
        val sorted = entries.sortedBy { it.odometerKm }
        val intervals = mutableListOf<MileageInterval>()
        var lastFull: FuelEntry? = null
        var litresSince = 0.0
        var costSince = 0.0
        for (e in sorted) {
            if (lastFull != null) {
                litresSince += e.litres
                costSince += e.totalCost
            }
            if (e.fullTank) {
                val prev = lastFull
                if (prev != null && e.odometerKm > prev.odometerKm && litresSince > 0) {
                    intervals += MileageInterval(e.id, e.odometerKm - prev.odometerKm, litresSince, costSince)
                }
                lastFull = e
                litresSince = 0.0
                costSince = 0.0
            }
        }
        val km = intervals.sumOf { it.km }
        val litres = intervals.sumOf { it.litres }
        val cost = intervals.sumOf { it.cost }
        return FuelSummary(
            intervals = intervals,
            averageKmPerLitre = if (litres > 0) km / litres else null,
            lastKmPerLitre = intervals.lastOrNull()?.kmPerLitre,
            averageCostPerKm = if (km > 0) cost / km else null,
            totalSpent = entries.sumOf { it.totalCost },
            totalLitres = entries.sumOf { it.litres },
        )
    }
}

private val Context.fuelStore: DataStore<Preferences> by preferencesDataStore(name = "fuel")

class FuelRepository(private val context: Context) {
    private val key = stringPreferencesKey("entries")

    val entries: Flow<List<FuelEntry>> = context.fuelStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> parse(p[key]).sortedByDescending { it.timeMs } }

    suspend fun add(entry: FuelEntry) {
        context.fuelStore.edit { p -> p[key] = encode(parse(p[key]) + entry) }
    }

    suspend fun delete(id: Long) {
        context.fuelStore.edit { p -> p[key] = encode(parse(p[key]).filterNot { it.id == id }) }
    }

    private fun parse(json: String?): List<FuelEntry> = runCatching {
        val arr = JSONArray(json ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FuelEntry(
                id = o.getLong("id"),
                timeMs = o.getLong("t"),
                odometerKm = o.getDouble("odo"),
                litres = o.getDouble("l"),
                totalCost = o.getDouble("cost"),
                fullTank = o.optBoolean("full", true),
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(list: List<FuelEntry>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("id", it.id).put("t", it.timeMs).put("odo", it.odometerKm)
                    .put("l", it.litres).put("cost", it.totalCost).put("full", it.fullTank),
            )
        }
    }.toString()
}
