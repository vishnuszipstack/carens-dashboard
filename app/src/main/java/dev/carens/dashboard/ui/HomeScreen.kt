package dev.carens.dashboard.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
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
import kotlin.math.roundToInt

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

private val Gap = 12.dp

/**
 * Home: a large live map in the middle, surrounded by widgets — speed & trip,
 * clock & weather, music — and a row of tiles for actions and apps.
 */
@Composable
fun HomeScreen(d: HomeData, music: MusicActions, a: HomeActions) {
    var confirmEnd by remember { mutableStateOf(false) }
    val tripActive = d.telemetry.trip != null
    val onTrip = { if (tripActive) confirmEnd = true else a.startTrip() }
    val imperial = d.settings.unit == SpeedUnit.MPH

    BoxWithConstraints(Modifier.fillMaxSize().padding(Gap)) {
        val landscape = maxWidth > maxHeight
        val tiles = @Composable { m: Modifier -> TileRow(d, tripActive, onTrip, a, m) }
        // The map takes 70% of the screen; the speed gauge rides on top of it.
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                MapHero(d, imperial, Modifier.weight(0.7f).fillMaxHeight(), a.openMap, a.openTrips)
                Column(Modifier.weight(0.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                    ClockWeatherCard(d.weather, d.place, Modifier.weight(1f).fillMaxWidth())
                    MusicCard(d.nowPlaying, d.musicAccess, music, Modifier.weight(1f).fillMaxWidth(), a.openMusic)
                    tiles(Modifier.fillMaxWidth().height(84.dp))
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                MapHero(d, imperial, Modifier.weight(0.7f).fillMaxWidth(), a.openMap, a.openTrips)
                Column(Modifier.weight(0.3f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                        ClockWeatherCard(d.weather, d.place, Modifier.weight(1f).fillMaxHeight())
                        MusicCard(d.nowPlaying, d.musicAccess, music, Modifier.weight(1f).fillMaxHeight(), a.openMusic)
                    }
                    tiles(Modifier.fillMaxWidth().height(84.dp))
                }
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

// --- Map --------------------------------------------------------------------------------

@Composable
private fun MapHero(d: HomeData, imperial: Boolean, modifier: Modifier, onOpen: () -> Unit, onSpeed: () -> Unit) {
    val t = d.telemetry
    val pos = t.latitude?.let { LatLon(it, t.longitude!!) }
    WidgetCard(modifier, padding = 0.dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gaugeSize = (minOf(maxWidth, maxHeight) * 0.38f).coerceIn(110.dp, 200.dp)
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
                    zoom = 16.0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The whole map is a button into the full map screen.
            Box(Modifier.fillMaxSize().clickable(onClick = onOpen))

            val nav = d.nav
            if (nav != null) {
                NavBanner(nav, imperial, Modifier.align(Alignment.TopStart).padding(10.dp).fillMaxWidth(), compact = true)
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ) { NavSummary(nav, imperial, Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
            } else {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("Where to?", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
                d.place?.let { p ->
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xCC0B1017),
                    ) {
                        Text(
                            p.label,
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                if (pos == null || !d.settings.showMap) {
                    Text(
                        if (pos == null) "Waiting for GPS…" else "Map",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 17.sp,
                    )
                }
            }

            // Speed rides on the map, bottom-right; tap for trips.
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(gaugeSize)
                    .clip(CircleShape).clickable(onClick = onSpeed),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 6.dp,
            ) {
                SpeedGauge(t, d.settings, Modifier.fillMaxSize().padding(4.dp))
            }
        }
    }
}

// --- Clock & weather ---------------------------------------------------------------------

@Composable
private fun ClockWeatherCard(weather: WeatherInfo?, place: PlaceInfo?, modifier: Modifier) {
    val (time, date) = rememberClock()
    WidgetCard(modifier, padding = 16.dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Fit the time to the card's width and leave room for date + weather below.
            val byWidth = maxWidth.value / (time.length.coerceAtLeast(5) * 0.58f)
            val byHeight = (maxHeight.value - if (weather != null) 84f else 30f) * 0.62f
            val timeSp = minOf(byWidth, byHeight).coerceIn(24f, 60f).sp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(time, fontSize = timeSp, lineHeight = timeSp, fontWeight = FontWeight.Light, style = Tabular, maxLines = 1)
                    Text(date, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (weather != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(weather.emoji, fontSize = 30.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(weather.tempLabel, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
                            Text(
                                weather.description + (weather.feelsLikeC?.let { " · feels ${it.roundToInt()}°" } ?: ""),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else if (place != null) {
                    Text("📍 ${place.label}", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// --- Music ---------------------------------------------------------------------------------

/** Album art fills the card behind a dark scrim, like a music app's now-playing tile. */
@Composable
private fun MusicCard(np: NowPlaying?, access: Boolean, actions: MusicActions, modifier: Modifier, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier.clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF2A2350), Color(0xFF13202E))))
            .clickable(onClick = onOpen),
    ) {
        val art = np?.art
        if (art != null) {
            val bmp = remember(art) { art.asImageBitmap() }
            Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x66000000), Color(0xE6000000))),
            ),
        )
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MusicNote, null, tint = DashColors.Violet, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    (np?.appName ?: "Music").uppercase(),
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                )
            }
            when {
                !access -> Column {
                    Text("Show what's playing", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tap to allow notification access",
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = actions.grantAccess),
                    )
                }
                np == null -> Column {
                    Text("Nothing playing", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tap to choose a music app", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(np.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        np.artist?.let {
                            Text(it, color = Color(0xFFCBD5E1), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = actions.previous, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Box(
                        Modifier.size(50.dp).clip(CircleShape).background(Color.White).clickable(onClick = actions.playPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (np.isPlaying) "Pause" else "Play",
                            tint = Color(0xFF111827),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    IconButton(onClick = actions.next, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

// --- Tiles ---------------------------------------------------------------------------------

@Composable
private fun TileRow(d: HomeData, tripActive: Boolean, onTrip: () -> Unit, a: HomeActions, modifier: Modifier) {
    val home = d.saved.firstOrNull { it.kind == PlaceKind.HOME }
    val work = d.saved.firstOrNull { it.kind == PlaceKind.WORK }
    LazyRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(end = 4.dp),
    ) {
        item {
            ActionTile(Icons.Filled.Mic, "Assistant", DashColors.Blue, onClick = a.assistant)
        }
        item {
            if (tripActive) ActionTile(Icons.Filled.Stop, "End trip", DashColors.Danger, onClick = onTrip)
            else ActionTile(Icons.Filled.Flag, "Start trip", DashColors.Accent, onClick = onTrip)
        }
        item {
            when {
                home != null -> ActionTile(Icons.Filled.Home, "Home", DashColors.Accent) { a.goTo(home) }
                else -> ActionTile(Icons.Filled.Home, "Set home", DashColors.Accent, onClick = a.openMap)
            }
        }
        if (work != null) item { ActionTile(Icons.Filled.Work, "Work", DashColors.Blue) { a.goTo(work) } }
        if (d.parking != null) item { ActionTile(Icons.Filled.DirectionsCar, "My car", DashColors.Warning, onClick = a.findCar) }
        items(d.dock, key = { it.packageName }) { app -> AppTile(app.icon, app.label) { a.launchApp(app.packageName) } }
        item { ActionTile(Icons.Filled.Apps, "All apps", MaterialTheme.colorScheme.onSurfaceVariant, onClick = a.openApps) }
    }
}

private val TileWidth: Dp = 104.dp

@Composable
fun ActionTile(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    WidgetCard(Modifier.width(TileWidth).fillMaxHeight(), onClick = onClick, padding = 10.dp) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AppTile(icon: Drawable?, label: String, width: Dp = TileWidth, iconSize: Dp = 40.dp, onClick: () -> Unit) {
    val bmp = remember(icon) { icon?.let { runCatching { it.toBitmap(160, 160).asImageBitmap() }.getOrNull() } }
    WidgetCard(Modifier.width(width).fillMaxHeight(), onClick = onClick, padding = 10.dp) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(iconSize)) else Spacer(Modifier.size(iconSize))
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Small icon + label used by the Music screen's app list. */
@Composable
fun DockIcon(icon: Drawable?, label: String, size: Int = 40, onClick: () -> Unit) {
    val bmp = remember(icon) { icon?.let { runCatching { it.toBitmap(144, 144).asImageBitmap() }.getOrNull() } }
    Column(
        Modifier.width((size + 36).dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bmp != null) Image(bmp, contentDescription = label, modifier = Modifier.size(size.dp)) else Box(Modifier.size(size.dp))
        Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Value + label, used by stats screens. */
@Composable
fun BigStat(value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1)
        Text(unit, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
