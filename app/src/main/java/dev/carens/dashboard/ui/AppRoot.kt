package dev.carens.dashboard.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.carens.dashboard.MainViewModel
import dev.carens.dashboard.location.LocationPermissions
import dev.carens.dashboard.service.DrivingService
import dev.carens.dashboard.settings.ThemeMode

private enum class Screen { DASHBOARD, TRIPS, SETTINGS }

@Composable
fun AppRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val place by vm.place.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val musicAccess by vm.musicAccess.collectAsStateWithLifecycle()
    val night by rememberIsNight()
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> night || isSystemInDarkTheme()
    }

    KeepScreenOn(settings.keepScreenOn)
    SystemBarIcons(dark)

    DashTheme(dark) {
        val context = LocalContext.current
        var granted by remember { mutableStateOf(LocationPermissions.hasPrecise(context)) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = LocationPermissions.hasPrecise(context)
            if (granted) DrivingService.start(context)
        }
        // Picks up a grant made in system settings.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            granted = LocationPermissions.hasPrecise(context)
            if (granted) DrivingService.start(context)
            vm.music.refresh() // picks up a fresh notification-access grant
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!granted) {
                PermissionScreen(
                    onRequest = { launcher.launch(LocationPermissions.toRequest()) },
                    onOpenSettings = { openAppSettings(context) },
                )
            } else {
                var screen by rememberSaveable { mutableStateOf(Screen.DASHBOARD) }
                BackHandler(enabled = screen != Screen.DASHBOARD) { screen = Screen.DASHBOARD }
                ImmersiveMode(enabled = settings.fullScreen && screen == Screen.DASHBOARD)

                when (screen) {
                    Screen.DASHBOARD -> DashboardScreen(
                        telemetry = telemetry,
                        settings = settings,
                        extras = DashExtras(
                            weather = weather,
                            place = place,
                            nowPlaying = nowPlaying,
                            musicAccess = musicAccess,
                            dark = dark,
                        ),
                        music = remember(vm) {
                            MusicActions(
                                grantAccess = {
                                    runCatching { context.startActivity(vm.music.accessSettingsIntent()) }
                                },
                                previous = { vm.music.previous() },
                                playPause = { vm.music.playPause() },
                                next = { vm.music.next() },
                            )
                        },
                        onStartTrip = vm::startTrip,
                        onStopTrip = vm::stopTrip,
                        onNavigate = { openMaps(context, telemetry.latitude, telemetry.longitude) },
                        onOpenTrips = { screen = Screen.TRIPS },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.TRIPS -> TripsScreen(
                        vm = vm,
                        unit = settings.unit,
                        onBack = { screen = Screen.DASHBOARD },
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        speedKmh = telemetry.gpsSpeedKmh,
                        onChange = vm::updateSettings,
                        onDeleteAllTrips = vm::deleteAllTrips,
                        update = UpdateUi(
                            state = updateState,
                            versionName = vm.updater.currentVersionName,
                            onCheck = vm::checkForUpdate,
                            onInstall = vm::installUpdate,
                            onAllowInstalls = {
                                runCatching { context.startActivity(vm.updater.installPermissionIntent()) }
                            },
                        ),
                        onBack = { screen = Screen.DASHBOARD },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    DisposableEffect(enabled, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            if (enabled) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    if (window != null) SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Location needed", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Speed, distance and trip time all come from your phone's GPS, so the dashboard " +
                "needs precise location while it's in use.\n\n" +
                "Trips are stored only on this phone. Nothing is uploaded, and you can delete " +
                "history at any time. A notification is shown whenever GPS is in use.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRequest, modifier = Modifier.widthIn(min = 240.dp).height(56.dp)) {
            Text("Allow precise location", fontSize = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().widthIn(max = 240.dp)) {
            Text("Open app settings")
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent) }
}

/** Hands off to Google Maps (centered on the current position); falls back to any map app. */
private fun openMaps(context: Context, lat: Double?, lon: Double?) {
    val uri = if (lat != null && lon != null) Uri.parse("geo:$lat,$lon?z=15") else Uri.parse("geo:0,0")
    val maps = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(maps)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
        }
    }
}
