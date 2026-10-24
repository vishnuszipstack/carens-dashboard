package dev.carens.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.carens.dashboard.service.DrivingService
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot(vm) }
        vm.handleLaunch(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        vm.handleLaunch(intent)
    }

    override fun onStart() {
        super.onStart()
        TelemetryHub.uiVisible.value = true
        DrivingService.start(this) // no-op until location permission is granted
    }

    override fun onStop() {
        TelemetryHub.uiVisible.value = false
        super.onStop()
    }
}
