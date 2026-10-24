package dev.carens.dashboard.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.carens.dashboard.apps.LaunchableApp
import dev.carens.dashboard.info.NowPlaying
import dev.carens.dashboard.info.PlaceInfo
import dev.carens.dashboard.info.WeatherInfo
import dev.carens.dashboard.nav.LatLon
import dev.carens.dashboard.nav.NavState
import dev.carens.dashboard.places.ParkingSpot
import dev.carens.dashboard.places.PlaceKind
import dev.carens.dashboard.places.SavedPlace
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.settings.SpeedUnit
import dev.carens.dashboard.telemetry.DashboardTelemetry

class HomeActions(
    val openMap: () -> Unit,
    val openMusic: () -> Unit,
    val openApps: () -> Unit,
    val openTrips: () -> Unit,
    val startTrip: () -> Unit,
    val stopTrip: () -> Unit,
    val assistant: () -> Unit,
    val goTo: (SavedPlace) -> Unit,
    val findCar: () -> Unit,
    val launchApp: (String) -> Unit,
)

data class HomeData(
    val telemetry: DashboardTelemetry,
    val settings: DashSettings,
    val weather: WeatherInfo?,
    val place: PlaceInfo?,
    val nowPlaying: NowPlaying?,
    val musicAccess: Boolean,
    val nav: NavState?,
    val saved: List<SavedPlace>,
    val parking: ParkingSpot?,
    val dock: List<LaunchableApp>,
    val dark: Boolean,
)

/** Car-launcher style home: big widgets you can read at a glance, plus an app dock. */
@Composable
fun HomeScreen(d: HomeData, music: MusicActions, a: HomeActions) {
    var confirmEnd by remember { mutableStateOf(false) }
    val tripActive = d.telemetry.trip != null
    val onTrip = { if (tripActive) confirmEnd = true else a.startTrip() }
    val imperial = d.settings.unit == SpeedUnit.MPH

    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        val landscape = maxWidth > maxHeight
        val gap = 10.dp
        if (landscape) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        WidgetCard(Modifier.weight(1.45f).fillMaxWidth(), padding = 8.dp) {
                            SpeedGauge(d.telemetry, d.settings, Modifier.fillMaxSize())
                        }
                        TripWidget(d.telemetry, d.settings, Modifier.weight(1f).fillMaxWidth(), onClick = a.openTrips)
                    }
                    Column(Modifier.weight(1.35f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        MapWidget(d, imperial, Modifier.weight(1.5f).fillMaxWidth(), a.openMap)
                        MusicWidget(d.nowPlaying, d.musicAccess, music, Modifier.weight(0.75f).fillMaxWidth(), onOpen = a.openMusic)
                    }
                    Column(Modifier.weight(0.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        ClockWidget(d.place?.label, Modifier.weight(1f).fillMaxWidth())
                        WeatherWidget(d.weather, Modifier.weight(0.8f).fillMaxWidth())
                        QuickActions(d, tripActive, onTrip, a, Modifier.weight(0.9f).fillMaxWidth())
                    }
                }
                Dock(d.dock, a.launchApp, a.openApps, Modifier.fillMaxWidth().height(76.dp))
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    ClockWidget(d.place?.label, Modifier.weight(1f).fillMaxHeight())
                    WeatherWidget(d.weather, Modifier.weight(1f).fillMaxHeight())
                }
                WidgetCard(Modifier.weight(1f).fillMaxWidth(), padding = 8.dp) {
                    SpeedGauge(d.telemetry, d.settings, Modifier.fillMaxSize())
                }
                MapWidget(d, imperial, Modifier.weight(1f).fillMaxWidth(), a.openMap)
                MusicWidget(d.nowPlaying, d.musicAccess, music, Modifier.fillMaxWidth().height(96.dp), onOpen = a.openMusic)
                TripWidget(d.telemetry, d.settings, Modifier.fillMaxWidth().height(110.dp), onClick = a.openTrips)
                QuickActions(d, tripActive, onTrip, a, Modifier.fillMaxWidth().height(110.dp))
                Dock(d.dock, a.launchApp, a.openApps, Modifier.fillMaxWidth().height(76.dp))
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this trip?") },
            text = { Text("It will be saved to your trip history.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; a.stopTrip() }) { Text("End trip") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun MapWidget(d: HomeData, imperial: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val t = d.telemetry
    val pos = t.latitude?.let { LatLon(it, t.longitude!!) }
    WidgetCard(modifier, padding = 0.dp) {
        Box(Modifier.fillMaxSize()) {
            if (d.settings.showMap) {
                OsmMap(
                    position = pos,
                    bearing = t.bearingDegrees,
                    dark = d.dark,
                    follow = true,
                    headingUp = d.nav != null && d.settings.mapHeadingUp,
                    route = d.nav?.route?.points.orEmpty(),
                    destination = d.nav?.destination?.point,
                    parking = d.parking?.point,
                    zoom = 15.5,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Transparent layer: the small map is a button that opens the full map.
            Box(Modifier.fillMaxSize().clickable(onClick = onOpen))
            val nav = d.nav
            if (nav != null) {
                NavBanner(nav, imperial, Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth(), compact = true)
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .clip(RoundedCornerShape(12.dp)).padding(0.dp),
                ) {
                    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                        NavSummary(nav, imperial, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            } else if (pos == null || !d.settings.showMap) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Map", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (pos == null) "Waiting for GPS…" else "Tap to open", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                androidx.compose.material3.Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ) {
                    Text("🔍  Where to?", fontSize = 17.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun TripWidget(t: DashboardTelemetry, s: DashSettings, modifier: Modifier, onClick: () -> Unit) {
    val u = s.unit
    val trip = t.trip
    WidgetCard(modifier, onClick = onClick) {
        WidgetLabel(if (trip == null) "Trip · not recording" else if (trip.autoStarted) "Trip · auto" else "Trip")
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            BigStat(trip?.let { Format.distance(it.distanceM, u) } ?: "—", u.distanceLabel, Modifier.weight(1f))
            val dur = trip?.let { Format.durationParts(it.elapsedMs) }
            BigStat(dur?.first ?: "—", dur?.second ?: "", Modifier.weight(1f))
            BigStat(trip?.avgMovingKmh?.let { Format.speed(u.fromKmh(it)) } ?: "—", "avg", Modifier.weight(1f))
            BigStat(trip?.maxSpeedKmh?.takeIf { it > 0 }?.let { Format.speed(u.fromKmh(it)) } ?: "—", "max", Modifier.weight(1f))
        }
    }
}

@Composable
fun BigStat(value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1)
        Text(unit, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun QuickActions(
    d: HomeData,
    tripActive: Boolean,
    onTrip: () -> Unit,
    a: HomeActions,
    modifier: Modifier,
) {
    val home = d.saved.firstOrNull { it.kind == PlaceKind.HOME }
    val work = d.saved.firstOrNull { it.kind == PlaceKind.WORK }
    WidgetCard(modifier, padding = 8.dp) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundAction(Icons.Filled.Mic, "Assistant", a.assistant)
            if (tripActive) {
                RoundAction(
                    Icons.Filled.Stop, "End trip", onTrip,
                    container = DashColors.Danger.copy(alpha = 0.2f), tint = DashColors.Danger,
                )
            } else {
                RoundAction(Icons.Filled.Flag, "Start trip", onTrip)
            }
            when {
                home != null -> RoundAction(Icons.Filled.Home, "Home", { a.goTo(home) })
                work != null -> RoundAction(Icons.Filled.Work, "Work", { a.goTo(work) })
                d.parking != null -> RoundAction(Icons.Filled.DirectionsCar, "My car", a.findCar)
                else -> RoundAction(Icons.Filled.Home, "Set home", a.openMap)
            }
        }
    }
}

@Composable
private fun Dock(apps: List<LaunchableApp>, launch: (String) -> Unit, openApps: () -> Unit, modifier: Modifier) {
    WidgetCard(modifier, padding = 6.dp) {
        LazyRow(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(apps, key = { it.packageName }) { app ->
                DockIcon(app.icon, app.label) { launch(app.packageName) }
            }
            item {
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = openApps).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Add, "All apps", modifier = Modifier.size(36.dp))
                    Text("All apps", fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun DockIcon(icon: Drawable?, label: String, size: Int = 40, onClick: () -> Unit) {
    val bmp = remember(icon) { icon?.let { runCatching { it.toBitmap(144, 144).asImageBitmap() }.getOrNull() } }
    Column(
        Modifier.width((size + 36).dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = label, modifier = Modifier.size(size.dp))
        } else {
            Box(Modifier.size(size.dp))
        }
        Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
