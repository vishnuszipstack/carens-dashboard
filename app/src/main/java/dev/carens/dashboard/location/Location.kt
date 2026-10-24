package dev.carens.dashboard.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.carens.dashboard.telemetry.GpsSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object LocationPermissions {
    fun hasPrecise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Location for speed; notifications so the trip-recording notice is visible. */
    fun toRequest(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}

/** 1 Hz high-accuracy fixes from the fused provider for as long as the flow is collected. */
@SuppressLint("MissingPermission")
fun locationUpdates(context: Context, intervalMs: Long = 1_000L): Flow<Location> = callbackFlow {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
        .setMinUpdateIntervalMillis(intervalMs / 2)
        .setWaitForAccurateLocation(false)
        .build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { trySend(it) }
        }
    }
    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        .addOnFailureListener { close(it) }
    awaitClose { client.removeLocationUpdates(callback) }
}

fun Location.toSample(): GpsSample = GpsSample(
    timeMs = elapsedRealtimeNanos / 1_000_000,
    wallTimeMs = time,
    lat = latitude,
    lon = longitude,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    accuracyM = if (hasAccuracy()) accuracy else null,
    altitudeM = if (hasAltitude()) altitude else null,
    bearingDeg = if (hasBearing()) bearing else null,
)
