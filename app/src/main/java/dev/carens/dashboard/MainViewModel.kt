package dev.carens.dashboard

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.carens.dashboard.apps.AppsRepository
import dev.carens.dashboard.fuel.FuelEntry
import dev.carens.dashboard.nav.Destination
import dev.carens.dashboard.nav.LatLon
import dev.carens.dashboard.nav.Route
import dev.carens.dashboard.places.PlaceKind
import dev.carens.dashboard.service.DrivingService
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.trips.TripEntity
import dev.carens.dashboard.trips.TripExport
import dev.carens.dashboard.update.ReleaseInfo
import dev.carens.dashboard.update.UpdateState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data class Results(val query: String, val items: List<Destination>) : SearchState
    data class Failed(val message: String) : SearchState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as DashApp).container

    val telemetry: StateFlow<DashboardTelemetry> = TelemetryHub.telemetry

    val settings: StateFlow<DashSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, DashSettings())

    val trips: StateFlow<List<TripEntity>> =
        container.trips.finishedTrips.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val weather = container.weather.weather
    val place = container.place.place
    val nowPlaying = container.music.nowPlaying
    val musicAccess = container.music.accessGranted
    val music = container.music

    // --- Navigation ------------------------------------------------------------

    val navigator = container.navigator
    val navState = navigator.state
    val routePreview = navigator.preview

    val savedPlaces = container.places.saved.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recentPlaces = container.places.recent.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val parking = container.places.parking.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = SearchState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // debounce typing
            _search.value = SearchState.Searching
            val t = telemetry.value
            val near = t.latitude?.let { LatLon(it, t.longitude!!) }
            _search.value = try {
                SearchState.Results(query, container.routes.search(query, near))
            } catch (e: Exception) {
                SearchState.Failed(e.message ?: "Search failed")
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchState.Idle
    }

    fun previewRoute(dest: Destination) {
        clearSearch()
        navigator.preview(dest)
    }

    fun cancelPreview() = navigator.clearPreview()

    fun startNavigation(dest: Destination, route: Route) {
        navigator.start(dest, route)
        container.appScope.launch { container.places.addRecent(dest) }
        DrivingService.start(getApplication())
    }

    fun stopNavigation() = navigator.stop()

    fun savePlace(kind: PlaceKind, dest: Destination) {
        container.appScope.launch { container.places.save(kind, dest) }
    }

    fun removePlace(id: Long) {
        container.appScope.launch { container.places.remove(id) }
    }

    fun clearParking() {
        container.appScope.launch { container.places.clearParking() }
    }

    fun parkHere() {
        val t = telemetry.value
        val lat = t.latitude ?: return
        container.appScope.launch { container.places.setParking(LatLon(lat, t.longitude!!)) }
    }

    /** Hands the destination to Google Maps for its own navigation. */
    fun openInGoogleMaps(dest: Destination) {
        val uri = android.net.Uri.parse("google.navigation:q=${dest.point.lat},${dest.point.lon}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }.onFailure {
            val geo = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:${dest.point.lat},${dest.point.lon}?q=${dest.point.lat},${dest.point.lon}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { getApplication<Application>().startActivity(geo) }
        }
    }

    // --- Fuel --------------------------------------------------------------------

    val fuelEntries = container.fuel.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addFuel(odometerKm: Double, litres: Double, totalCost: Double, fullTank: Boolean) {
        val now = System.currentTimeMillis()
        container.appScope.launch {
            container.fuel.add(FuelEntry(now, now, odometerKm, litres, totalCost, fullTank))
        }
    }

    fun deleteFuel(id: Long) {
        container.appScope.launch { container.fuel.delete(id) }
    }

    // --- Apps ---------------------------------------------------------------------

    val apps: AppsRepository = container.apps

    // --- Updates -----------------------------------------------------------------

    val updater = container.updater
    val updateState: StateFlow<UpdateState> = updater.state

    fun checkForUpdate() {
        container.appScope.launch { updater.check() }
    }

    fun installUpdate(release: ReleaseInfo) {
        container.appScope.launch { updater.downloadAndInstall(release) }
    }

    // --- Settings & trips ---------------------------------------------------------

    fun updateSettings(transform: (DashSettings) -> DashSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    fun startTrip() {
        DrivingService.send(getApplication(), DrivingService.ACTION_START_TRIP)
    }

    fun stopTrip() {
        DrivingService.send(getApplication(), DrivingService.ACTION_STOP_TRIP)
    }

    fun deleteTrip(id: Long) {
        container.appScope.launch { container.trips.delete(id) }
    }

    fun deleteAllTrips() {
        container.appScope.launch { container.trips.deleteAll() }
    }

    fun exportTrips() {
        runCatching { TripExport.share(getApplication(), trips.value) }
    }
}
