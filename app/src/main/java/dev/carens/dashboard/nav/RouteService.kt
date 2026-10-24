package dev.carens.dashboard.nav

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import dev.carens.dashboard.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Place search and driving routes without API keys:
 *  - Search: Android's built-in Geocoder, falling back to OpenStreetMap Nominatim.
 *  - Routes: the public OSRM server (OpenStreetMap data), with turn-by-turn steps.
 * Both public services are meant for light personal use, which this is.
 */
class RouteService(private val context: Context) {

    private val userAgent = "CarensDashboard/${BuildConfig.VERSION_NAME} (personal use)"

    suspend fun search(query: String, near: LatLon?): List<Destination> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val fromGeocoder = runCatching { geocoderSearch(q, near) }.getOrDefault(emptyList())
        if (fromGeocoder.isNotEmpty()) return fromGeocoder
        return runCatching { nominatimSearch(q, near) }.getOrDefault(emptyList())
    }

    private suspend fun geocoderSearch(q: String, near: LatLon?): List<Destination> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        val results: List<Address> = withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.toList())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    }
                    if (near != null) {
                        geocoder.getFromLocationName(
                            q, 8, near.lat - 1.5, near.lon - 1.5, near.lat + 1.5, near.lon + 1.5, listener,
                        )
                    } else {
                        geocoder.getFromLocationName(q, 8, listener)
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    if (near != null) {
                        geocoder.getFromLocationName(q, 8, near.lat - 1.5, near.lon - 1.5, near.lat + 1.5, near.lon + 1.5)
                    } else {
                        geocoder.getFromLocationName(q, 8)
                    }.orEmpty()
                }
            }
        }.orEmpty()
        return results.filter { it.hasLatitude() && it.hasLongitude() }.map { a ->
            val line = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ")
            Destination(
                name = a.featureName?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) } ?: q,
                address = line.ifBlank { null },
                point = LatLon(a.latitude, a.longitude),
            )
        }
    }

    private suspend fun nominatimSearch(q: String, near: LatLon?): List<Destination> = withContext(Dispatchers.IO) {
        val box = near?.let {
            "&viewbox=${it.lon - 1.5},${it.lat + 1.5},${it.lon + 1.5},${it.lat - 1.5}"
        } ?: ""
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8" +
            "&q=${URLEncoder.encode(q, "UTF-8")}$box"
        val arr = JSONArray(get(url))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            Destination(
                name = o.optString("name").ifBlank { display.substringBefore(",") },
                address = display,
                point = LatLon(o.getString("lat").toDouble(), o.getString("lon").toDouble()),
            )
        }
    }

    suspend fun route(from: LatLon, to: LatLon, bearing: Float?): Route = withContext(Dispatchers.IO) {
        // Passing the current heading stops OSRM from routing a U-turn as the first step.
        val bearings = bearing?.let { "&bearings=${it.toInt().mod(360)},45;" } ?: ""
        val url = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f" +
                "?overview=full&geometries=geojson&steps=true",
            from.lon, from.lat, to.lon, to.lat,
        ) + bearings
        val json = JSONObject(get(url))
        check(json.optString("code") == "Ok") { json.optString("message", "No route found") }
        parseRoute(json.getJSONArray("routes").getJSONObject(0))
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("message") }.getOrNull()
                throw IllegalStateException(msg?.ifBlank { null } ?: "Server error $code")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun parseRoute(r: JSONObject): Route {
            val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
            val points = (0 until coords.length()).map {
                val c = coords.getJSONArray(it)
                LatLon(c.getDouble(1), c.getDouble(0))
            }
            val steps = mutableListOf<RouteStep>()
            val legs = r.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val arr = legs.getJSONObject(l).getJSONArray("steps")
                for (i in 0 until arr.length()) steps += parseStep(arr.getJSONObject(i))
            }
            return Route(points, steps, r.getDouble("distance"), r.getDouble("duration"))
        }

        private fun parseStep(s: JSONObject): RouteStep {
            val m = s.getJSONObject("maneuver")
            val type = m.optString("type")
            val modifier = m.optString("modifier")
            val loc = m.getJSONArray("location")
            val name = s.optString("name").ifBlank { s.optString("ref") }
            val maneuver = maneuverOf(type, modifier)
            return RouteStep(
                maneuver = maneuver,
                instruction = instruction(type, modifier, name, m.optInt("exit", 0), maneuver),
                roadName = name,
                location = LatLon(loc.getDouble(1), loc.getDouble(0)),
                distanceM = s.optDouble("distance", 0.0),
                durationS = s.optDouble("duration", 0.0),
            )
        }

        fun maneuverOf(type: String, modifier: String): Maneuver = when (type) {
            "depart" -> Maneuver.DEPART
            "arrive" -> Maneuver.ARRIVE
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> Maneuver.ROUNDABOUT
            "merge" -> Maneuver.MERGE
            "on ramp", "off ramp" -> if (modifier.contains("left")) Maneuver.RAMP_LEFT else Maneuver.RAMP_RIGHT
            "fork" -> if (modifier.contains("left")) Maneuver.FORK_LEFT else Maneuver.FORK_RIGHT
            else -> when (modifier) {
                "uturn" -> Maneuver.UTURN
                "sharp left" -> Maneuver.SHARP_LEFT
                "left" -> Maneuver.LEFT
                "slight left" -> Maneuver.SLIGHT_LEFT
                "sharp right" -> Maneuver.SHARP_RIGHT
                "right" -> Maneuver.RIGHT
                "slight right" -> Maneuver.SLIGHT_RIGHT
                else -> Maneuver.STRAIGHT
            }
        }

        fun instruction(type: String, modifier: String, name: String, exit: Int, m: Maneuver): String {
            val onto = if (name.isNotBlank()) " onto $name" else ""
            val dir = when (modifier) {
                "uturn" -> "Make a U-turn"
                "sharp left" -> "Turn sharp left"
                "left" -> "Turn left"
                "slight left" -> "Keep slightly left"
                "sharp right" -> "Turn sharp right"
                "right" -> "Turn right"
                "slight right" -> "Keep slightly right"
                else -> "Continue straight"
            }
            return when (type) {
                "depart" -> if (name.isNotBlank()) "Head out on $name" else "Start driving"
                "arrive" -> "Arrive at your destination"
                "roundabout", "rotary" ->
                    if (exit > 0) "At the roundabout, take exit $exit$onto" else "Enter the roundabout"
                "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
                "merge" -> "Merge$onto"
                "on ramp" -> "Take the ramp$onto"
                "off ramp" -> "Take the exit$onto"
                "fork" -> (if (m == Maneuver.FORK_LEFT) "Keep left at the fork" else "Keep right at the fork") + onto
                "end of road" -> "At the end of the road, ${dir.lowercase()}$onto"
                "continue", "new name" -> if (modifier == "straight" || modifier.isBlank()) "Continue$onto" else "$dir$onto"
                else -> "$dir$onto"
            }
        }
    }
}
