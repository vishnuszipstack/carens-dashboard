package dev.carens.dashboard

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.carens.dashboard.settings.SettingsRepository
import dev.carens.dashboard.trips.AppDatabase
import dev.carens.dashboard.trips.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DashApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency wiring; small enough that a DI framework isn't worth it yet. */
class AppContainer(context: Context) {
    private val db: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "carens_dashboard.db",
    ).build()

    val trips = TripRepository(db.tripDao())
    val settings = SettingsRepository(context.applicationContext)

    /** For writes that must finish even if the screen or service that started them goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
