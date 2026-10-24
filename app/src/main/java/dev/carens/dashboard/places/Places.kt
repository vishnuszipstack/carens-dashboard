package dev.carens.dashboard.places

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.carens.dashboard.nav.Destination
import dev.carens.dashboard.nav.LatLon
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

enum class PlaceKind { HOME, WORK, FAVORITE }

data class SavedPlace(
    val id: Long,
    val kind: PlaceKind,
    val name: String,
    val address: String?,
    val point: LatLon,
) {
    fun toDestination() = Destination(name, address, point)
}

data class ParkingSpot(val point: LatLon, val savedAtMs: Long, val note: String?)

private val Context.placesStore: DataStore<Preferences> by preferencesDataStore(name = "places")

/** Home, Work, favourites and the last parking spot. Stored on the phone only. */
class PlacesRepository(private val context: Context) {
    private val placesKey = stringPreferencesKey("saved")
    private val parkingKey = stringPreferencesKey("parking")
    private val recentKey = stringPreferencesKey("recent")

    private val data = context.placesStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val saved: Flow<List<SavedPlace>> = data.map { p -> parsePlaces(p[placesKey]) }
    val recent: Flow<List<Destination>> = data.map { p -> parseDestinations(p[recentKey]) }
    val parking: Flow<ParkingSpot?> = data.map { p ->
        p[parkingKey]?.let {
            runCatching {
                val o = JSONObject(it)
                ParkingSpot(LatLon(o.getDouble("lat"), o.getDouble("lon")), o.getLong("t"), o.optString("note").ifBlank { null })
            }.getOrNull()
        }
    }

    suspend fun save(kind: PlaceKind, dest: Destination) {
        context.placesStore.edit { p ->
            val list = parsePlaces(p[placesKey]).toMutableList()
            // Only one Home and one Work.
            if (kind != PlaceKind.FAVORITE) list.removeAll { it.kind == kind }
            list += SavedPlace(System.currentTimeMillis(), kind, if (kind == PlaceKind.FAVORITE) dest.name else kind.label, dest.address, dest.point)
            p[placesKey] = encodePlaces(list)
        }
    }

    suspend fun remove(id: Long) {
        context.placesStore.edit { p ->
            p[placesKey] = encodePlaces(parsePlaces(p[placesKey]).filterNot { it.id == id })
        }
    }

    suspend fun addRecent(dest: Destination) {
        context.placesStore.edit { p ->
            val list = parseDestinations(p[recentKey]).filterNot { it.name == dest.name && it.point == dest.point }
            p[recentKey] = encodeDestinations((listOf(dest) + list).take(10))
        }
    }

    suspend fun setParking(point: LatLon, note: String? = null) {
        context.placesStore.edit { p ->
            p[parkingKey] = JSONObject()
                .put("lat", point.lat).put("lon", point.lon)
                .put("t", System.currentTimeMillis()).put("note", note ?: "")
                .toString()
        }
    }

    suspend fun clearParking() {
        context.placesStore.edit { it.remove(parkingKey) }
    }

    private fun parsePlaces(json: String?): List<SavedPlace> = runCatching {
        val arr = JSONArray(json ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedPlace(
                id = o.getLong("id"),
                kind = runCatching { PlaceKind.valueOf(o.getString("kind")) }.getOrDefault(PlaceKind.FAVORITE),
                name = o.getString("name"),
                address = o.optString("address").ifBlank { null },
                point = LatLon(o.getDouble("lat"), o.getDouble("lon")),
            )
        }
    }.getOrDefault(emptyList())

    private fun encodePlaces(list: List<SavedPlace>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("id", it.id).put("kind", it.kind.name).put("name", it.name)
                    .put("address", it.address ?: "").put("lat", it.point.lat).put("lon", it.point.lon),
            )
        }
    }.toString()

    private fun parseDestinations(json: String?): List<Destination> = runCatching {
        val arr = JSONArray(json ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Destination(o.getString("name"), o.optString("address").ifBlank { null }, LatLon(o.getDouble("lat"), o.getDouble("lon")))
        }
    }.getOrDefault(emptyList())

    private fun encodeDestinations(list: List<Destination>): String = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("name", it.name).put("address", it.address ?: "").put("lat", it.point.lat).put("lon", it.point.lon))
        }
    }.toString()
}

val PlaceKind.label: String
    get() = when (this) {
        PlaceKind.HOME -> "Home"
        PlaceKind.WORK -> "Work"
        PlaceKind.FAVORITE -> "Favourite"
    }
