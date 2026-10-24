package dev.carens.dashboard.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import dev.carens.dashboard.apps.AppsRepository
import dev.carens.dashboard.nav.Destination
import dev.carens.dashboard.nav.LatLon
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

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Home", Icons.Filled.SpaceDashboard),
    MAP("Map", Icons.Filled.Map),
    MUSIC("Music", Icons.Filled.LibraryMusic),
    APPS("Apps", Icons.Filled.Apps),
    MORE("More", Icons.Filled.MoreHoriz),
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val place by vm.place.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val musicAccess by vm.musicAccess.collectAsStateWithLifecycle()
    val navState by vm.navState.collectAsStateWithLifecycle()
    val preview by vm.routePreview.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val saved by vm.savedPlaces.collectAsStateWithLifecycle()
    val recent by vm.recentPlaces.collectAsStateWithLifecycle()
    val parking by vm.parking.collectAsStateWithLifecycle()
    val fuel by vm.fuelEntries.collectAsStateWithLifecycle()
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
                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                var more by rememberSaveable { mutableStateOf(MorePage.HUB) }
                BackHandler(enabled = tab != Tab.HOME || more != MorePage.HUB) {
                    if (tab == Tab.MORE && more != MorePage.HUB) more = MorePage.HUB else tab = Tab.HOME
                }
                ImmersiveMode(enabled = settings.fullScreen)

                val musicActions = remember(vm) {
                    MusicActions(
                        grantAccess = { runCatching { context.startActivity(vm.music.accessSettingsIntent()) } },
                        previous = { vm.music.previous() },
                        playPause = { vm.music.playPause() },
                        next = { vm.music.next() },
                    )
                }
                val goTo: (Destination) -> Unit = { dest ->
                    vm.previewRoute(dest)
                    tab = Tab.MAP
                }
                val dockPackages = settings.dockApps.ifEmpty {
                    AppsRepository.DEFAULT_DOCK.filter { vm.apps.isInstalled(it) }
                }
                val dockApps = remember(dockPackages) { dockPackages.map { vm.apps.app(it) } }
                val pos = telemetry.latitude?.let { LatLon(it, telemetry.longitude!!) }

                Row(Modifier.fillMaxSize()) {
                    NavigationRail(
                        modifier = if (settings.fullScreen) Modifier else Modifier.safeDrawingPadding(),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        Spacer(Modifier.weight(1f))
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t,
                                onClick = {
                                    if (t == Tab.MORE && tab == Tab.MORE) more = MorePage.HUB
                                    tab = t
                                },
                                icon = {
                                    val badge = (t == Tab.MAP && navState != null) ||
                                        (t == Tab.MORE && updateState is dev.carens.dashboard.update.UpdateState.Available)
                                    BadgedBox(badge = { if (badge) Badge() }) {
                                        Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(30.dp))
                                    }
                                },
                                label = { Text(t.label, fontSize = 13.sp) },
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .then(if (settings.fullScreen) Modifier else Modifier.safeDrawingPadding()),
                    ) {
                        when (tab) {
                            Tab.HOME -> HomeScreen(
                                d = HomeData(
                                    telemetry = telemetry,
                                    settings = settings,
                                    weather = weather,
                                    place = place,
                                    nowPlaying = nowPlaying,
                                    musicAccess = musicAccess,
                                    nav = navState,
                                    saved = saved,
                                    parking = parking,
                                    dock = dockApps,
                                    dark = dark,
                                ),
                                music = musicActions,
                                a = HomeActions(
                                    openMap = { tab = Tab.MAP },
                                    openMusic = { tab = Tab.MUSIC },
                                    openApps = { tab = Tab.APPS },
                                    openTrips = { tab = Tab.MORE; more = MorePage.TRIPS },
                                    startTrip = vm::startTrip,
                                    stopTrip = vm::stopTrip,
                                    assistant = { vm.apps.assistant() },
                                    goTo = { goTo(it.toDestination()) },
                                    findCar = { parking?.let { goTo(Destination("Your parked car", null, it.point)) } },
                                    launchApp = { vm.apps.launch(it) },
                                ),
                            )
                            Tab.MAP -> MapScreen(
                                d = MapData(telemetry, settings, dark, search, preview, navState, saved, recent, parking),
                                a = MapActions(
                                    search = vm::search,
                                    clearSearch = vm::clearSearch,
                                    preview = vm::previewRoute,
                                    cancelPreview = vm::cancelPreview,
                                    start = vm::startNavigation,
                                    stop = vm::stopNavigation,
                                    save = vm::savePlace,
                                    openInGoogleMaps = vm::openInGoogleMaps,
                                    toggleVoice = { vm.updateSettings { it.copy(voiceGuidance = !it.voiceGuidance) } },
                                    toggleHeadingUp = { vm.updateSettings { it.copy(mapHeadingUp = !it.mapHeadingUp) } },
                                    parkHere = vm::parkHere,
                                ),
                            )
                            Tab.MUSIC -> MusicScreen(nowPlaying, musicAccess, vm.music, musicActions.grantAccess)
                            Tab.APPS -> AppsScreen(vm.apps, dockPackages) { pkg ->
                                vm.updateSettings { s ->
                                    val current = s.dockApps.ifEmpty { dockPackages }
                                    s.copy(dockApps = if (pkg in current) current - pkg else current + pkg)
                                }
                            }
                            Tab.MORE -> when (more) {
                                MorePage.HUB -> MoreHub(
                                    fuelSummary = dev.carens.dashboard.fuel.FuelMath.summarize(fuel).averageKmPerLitre
                                        ?.let { String.format(java.util.Locale.US, "Average %.1f km/l", it) }
                                        ?: "Track fill-ups and mileage",
                                    placesCount = saved.size,
                                    parking = parking,
                                    position = pos,
                                    updateAvailable = updateState is dev.carens.dashboard.update.UpdateState.Available,
                                    onOpen = { more = it },
                                    onFindCar = { parking?.let { goTo(Destination("Your parked car", null, it.point)) } },
                                    onParkHere = vm::parkHere,
                                )
                                MorePage.TRIPS -> TripsScreen(vm, settings.unit, onBack = { more = MorePage.HUB })
                                MorePage.FUEL -> FuelScreen(fuel, vm::addFuel, vm::deleteFuel, onBack = { more = MorePage.HUB })
                                MorePage.PLACES -> PlacesScreen(
                                    saved,
                                    onGo = { goTo(it.toDestination()) },
                                    onRemove = vm::removePlace,
                                    onBack = { more = MorePage.HUB },
                                )
                                MorePage.SETTINGS -> SettingsScreen(
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
                                    onBack = { more = MorePage.HUB },
                                )
                            }
                        }
                    }
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
