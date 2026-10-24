package dev.carens.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.carens.dashboard.service.DrivingService
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.trips.TripEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as DashApp).container

    val telemetry: StateFlow<DashboardTelemetry> = TelemetryHub.telemetry

    val settings: StateFlow<DashSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, DashSettings())

    val trips: StateFlow<List<TripEntity>> =
        container.trips.finishedTrips.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateSettings(transform: (DashSettings) -> DashSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    fun startTrip() = DrivingService.send(getApplication(), DrivingService.ACTION_START_TRIP)
    fun stopTrip() = DrivingService.send(getApplication(), DrivingService.ACTION_STOP_TRIP)

    fun deleteTrip(id: Long) {
        container.appScope.launch { container.trips.delete(id) }
    }

    fun deleteAllTrips() {
        container.appScope.launch { container.trips.deleteAll() }
    }
}
