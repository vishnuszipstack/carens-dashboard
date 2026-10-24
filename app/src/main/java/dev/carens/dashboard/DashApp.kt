package dev.carens.dashboard

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.carens.dashboard.info.MapTiles
import dev.carens.dashboard.info.NowPlayingRepository
import dev.carens.dashboard.info.PlaceRepository
import dev.carens.dashboard.info.WeatherRepository
import dev.carens.dashboard.fuel.FuelRepository
import dev.carens.dashboard.nav.Navigator
import dev.carens.dashboard.nav.RouteService
import dev.carens.dashboard.places.PlacesRepository
import dev.carens.dashboard.settings.SettingsRepository
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.trips.AppDatabase
import dev.carens.dashboard.trips.TripRepository
import dev.carens.dashboard.update.Updater
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class DashApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            userAgentValue = "CarensDashboard/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
        container = AppContainer(this)
        container.weather.start()
        container.place.start()
        container.music.refresh()
        container.appScope.launch {
            container.settings.settings.collect {
                container.navigator.voiceEnabled = it.voiceGuidance
                container.navigator.imperial = it.unit == dev.carens.dashboard.settings.SpeedUnit.MPH
            }
        }
    }
}

/** Manual dependency wiring; small enough that a DI framework isn't worth it yet. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    private val db: AppDatabase = Room.databaseBuilder(
        app,
        AppDatabase::class.java,
        "carens_dashboard.db",
    ).build()

    /** For writes that must finish even if the screen or service that started them goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val trips = TripRepository(db.tripDao())
    val settings = SettingsRepository(app)
    val updater = Updater(app)
    val weather = WeatherRepository(appScope, TelemetryHub.telemetry)
    val place = PlaceRepository(app, appScope, TelemetryHub.telemetry)
    val music = NowPlayingRepository(app)
    val mapTiles = MapTiles(app)
    val places = PlacesRepository(app)
    val fuel = FuelRepository(app)
    val routes = RouteService(app)
    val apps = dev.carens.dashboard.apps.AppsRepository(app)

    /** Navigation runs on the main thread scope so state updates stay ordered. */
    val navigator = Navigator(app, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), routes)
}
