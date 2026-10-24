package dev.carens.dashboard.info

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.distanceMeters
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class PlaceInfo(val name: String, val area: String?, val lat: Double, val lon: Double) {
    val label: String get() = listOfNotNull(name, area?.takeIf { it != name }).joinToString(", ")
}

/** Area name for the current position via Android's built-in geocoder (no key). */
class PlaceRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val telemetry: StateFlow<DashboardTelemetry>,
) {
    private val _place = MutableStateFlow<PlaceInfo?>(null)
    val place: StateFlow<PlaceInfo?> = _place.asStateFlow()

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        if (!Geocoder.isPresent()) return
        scope.launch {
            telemetry.sample(15_000).collectLatest { t ->
                val lat = t.latitude ?: return@collectLatest
                val lon = t.longitude ?: return@collectLatest
                val current = _place.value
                if (current != null && distanceMeters(current.lat, current.lon, lat, lon) < 300) return@collectLatest
                lookup(lat, lon)?.let { a ->
                    val name = a.subLocality ?: a.thoroughfare ?: a.locality ?: a.subAdminArea ?: return@let
                    val area = a.locality ?: a.subAdminArea ?: a.adminArea
                    _place.value = PlaceInfo(name, area, lat, lon)
                }
            }
        }
    }

    private suspend fun lookup(lat: Double, lon: Double): Address? = withTimeoutOrNull(10_000) {
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(
                    lat,
                    lon,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            }
        }
    }
}
