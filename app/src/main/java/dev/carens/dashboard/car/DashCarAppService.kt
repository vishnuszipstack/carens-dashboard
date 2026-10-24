package dev.carens.dashboard.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.carens.dashboard.location.LocationPermissions
import dev.carens.dashboard.service.DrivingService
import dev.carens.dashboard.telemetry.TelemetryHub

/**
 * Entry point for Android Auto. Android Auto can only show template-based
 * screens, so the car view is a compact text read-out of the same live data
 * the phone dashboard shows.
 */
class DashCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Android Auto and the official desktop head unit.
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = DashSession()
}

class DashSession : Session() {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                TelemetryHub.carVisible.value = true
                // Starts GPS/trip recording even if the phone app was never opened.
                DrivingService.start(carContext)
            }

            override fun onStop(owner: LifecycleOwner) {
                TelemetryHub.carVisible.value = false
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen =
        if (LocationPermissions.hasPrecise(carContext)) DashCarScreen(carContext)
        else PermissionCarScreen(carContext)
}
