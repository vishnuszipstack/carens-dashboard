package dev.carens.dashboard.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.carens.dashboard.SearchState
import dev.carens.dashboard.nav.Destination
import dev.carens.dashboard.nav.LatLon
import dev.carens.dashboard.nav.NavState
import dev.carens.dashboard.nav.Navigator
import dev.carens.dashboard.nav.navDistance
import dev.carens.dashboard.places.ParkingSpot
import dev.carens.dashboard.places.PlaceKind
import dev.carens.dashboard.places.SavedPlace
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.settings.SpeedUnit
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.distanceMeters
import kotlin.math.roundToInt

class MapActions(
    val search: (String) -> Unit,
    val clearSearch: () -> Unit,
    val preview: (Destination) -> Unit,
    val cancelPreview: () -> Unit,
    val start: (Destination, dev.carens.dashboard.nav.Route) -> Unit,
    val stop: () -> Unit,
    val save: (PlaceKind, Destination) -> Unit,
    val openInGoogleMaps: (Destination) -> Unit,
    val toggleVoice: () -> Unit,
    val toggleHeadingUp: () -> Unit,
    val parkHere: () -> Unit,
)

data class MapData(
    val telemetry: DashboardTelemetry,
    val settings: DashSettings,
    val dark: Boolean,
    val search: SearchState,
    val preview: Navigator.Preview,
    val nav: NavState?,
    val saved: List<SavedPlace>,
    val recent: List<Destination>,
    val parking: ParkingSpot?,
)

/** Full-screen map with search, route preview and turn-by-turn guidance. */
@Composable
fun MapScreen(d: MapData, a: MapActions) {
    val t = d.telemetry
    val pos = t.latitude?.let { LatLon(it, t.longitude!!) }
    val imperial = d.settings.unit == SpeedUnit.MPH
    val controls = remember { MapControls() }
    var lastPanMs by remember { mutableLongStateOf(0L) }
    val following = SystemClock.elapsedRealtime() - lastPanMs > (if (d.nav != null) 10_000 else 30_000)
    val previewRoute = (d.preview as? Navigator.Preview.Ready)?.route
    val routePoints = d.nav?.route?.points ?: previewRoute?.points.orEmpty()
    val destination = d.nav?.destination?.point ?: when (val p = d.preview) {
        is Navigator.Preview.Ready -> p.destination.point
        is Navigator.Preview.Loading -> p.destination.point
        is Navigator.Preview.Failed -> p.destination.point
        Navigator.Preview.None -> null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 600.dp
        OsmMap(
            position = pos,
            bearing = t.bearingDegrees,
            dark = d.dark,
            follow = following && previewRoute == null,
            headingUp = d.settings.mapHeadingUp && d.nav != null,
            route = routePoints,
            destination = destination,
            parking = d.parking?.point,
            zoom = 16.5,
            controls = controls,
            onUserMoved = { lastPanMs = SystemClock.elapsedRealtime() },
            onLongPress = { p -> a.preview(Destination("Dropped pin", String.format(java.util.Locale.US, "%.5f, %.5f", p.lat, p.lon), p)) },
            modifier = Modifier.fillMaxSize(),
        )

        // Top-left: guidance banner while navigating, otherwise search.
        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp).then(if (wide) Modifier.width(430.dp) else Modifier.fillMaxWidth()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val nav = d.nav
            if (nav != null) {
                NavBanner(nav, imperial, Modifier.fillMaxWidth())
            } else {
                SearchPanel(d, a, pos)
            }
        }

        // Right: big map controls.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapButton(Icons.Filled.Add, "Zoom in") { controls.zoomIn() }
            MapButton(Icons.Filled.Remove, "Zoom out") { controls.zoomOut() }
            MapButton(
                if (d.settings.mapHeadingUp) Icons.Filled.Navigation else Icons.Filled.Explore,
                if (d.settings.mapHeadingUp) "Heading up" else "North up",
                a.toggleHeadingUp,
            )
            MapButton(
                Icons.Filled.MyLocation,
                "Follow my position",
                highlighted = !following,
            ) { lastPanMs = 0L }
        }

        // Bottom-left: speed bubble.
        SpeedBubble(t, d.settings, Modifier.align(Alignment.BottomStart).padding(12.dp))

        // Bottom: route preview card or navigation bar.
        Box(
            Modifier.align(Alignment.BottomCenter).padding(start = 110.dp, end = 80.dp, bottom = 12.dp)
                .then(if (wide) Modifier.widthIn(max = 640.dp) else Modifier.fillMaxWidth()),
        ) {
            val nav = d.nav
            when {
                nav != null -> NavBar(nav, imperial, d.settings.voiceGuidance, a)
                d.preview !is Navigator.Preview.None -> PreviewCard(d.preview, imperial, a, controls)
            }
        }
    }
}

@Composable
private fun SearchPanel(d: MapData, a: MapActions, pos: LatLon?) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    a.search(it)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search places", fontSize = 18.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; a.clearSearch(); focus.clearFocus() }) {
                            Icon(Icons.Filled.Close, "Clear")
                        }
                    }
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { a.search(query); focus.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            when (val s = d.search) {
                SearchState.Idle -> QuickPlaces(d, a)
                SearchState.Searching -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Searching…")
                }
                is SearchState.Failed -> Text(s.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                is SearchState.Results -> {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (s.items.isEmpty()) {
                        Text("No places found for \"${s.query}\"", Modifier.padding(16.dp))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(s.items) { dest ->
                                ResultRow(dest, pos, d.settings.unit == SpeedUnit.MPH) {
                                    focus.clearFocus()
                                    query = dest.name
                                    a.preview(dest)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPlaces(d: MapData, a: MapActions) {
    val chips = buildList<Triple<ImageVector, String, () -> Unit>> {
        d.saved.sortedBy { it.kind.ordinal }.forEach { p ->
            val icon = when (p.kind) {
                PlaceKind.HOME -> Icons.Filled.Home
                PlaceKind.WORK -> Icons.Filled.Work
                PlaceKind.FAVORITE -> Icons.Filled.Star
            }
            add(Triple(icon, p.name) { a.preview(p.toDestination()) })
        }
        d.parking?.let { spot ->
            add(Triple(Icons.Filled.LocalParking, "My car") {
                a.preview(Destination("Your parked car", null, spot.point))
            })
        }
        d.recent.take(4).forEach { r -> add(Triple(Icons.Filled.History, r.name) { a.preview(r) }) }
    }
    if (chips.isEmpty()) {
        Text(
            "Tip: long-press anywhere on the map to drive there.",
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
        return
    }
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips) { (icon, label, onClick) ->
            AssistChip(
                onClick = onClick,
                label = { Text(label, maxLines = 1, fontSize = 15.sp) },
                leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
}

@Composable
private fun ResultRow(dest: Destination, pos: LatLon?, imperial: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(dest.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            dest.address?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (pos != null) {
            Text(
                navDistance(distanceMeters(pos.lat, pos.lon, dest.point.lat, dest.point.lon), imperial),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewCard(p: Navigator.Preview, imperial: Boolean, a: MapActions, controls: MapControls) {
    val dest = when (p) {
        is Navigator.Preview.Ready -> p.destination
        is Navigator.Preview.Loading -> p.destination
        is Navigator.Preview.Failed -> p.destination
        Navigator.Preview.None -> null
    } ?: return
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dest.name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    dest.address?.let {
                        Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = a.cancelPreview) { Icon(Icons.Filled.Close, "Close") }
            }
            Spacer(Modifier.height(8.dp))
            when (p) {
                is Navigator.Preview.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Finding the best route…")
                }
                is Navigator.Preview.Failed -> Text(p.message, color = MaterialTheme.colorScheme.error)
                is Navigator.Preview.Ready -> {
                    androidx.compose.runtime.LaunchedEffect(p.route) { controls.showWholeRoute(p.route.points) }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            Format.duration((p.route.durationS * 1000).toLong()),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(navDistance(p.route.distanceM, imperial), fontSize = 18.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Text(
                        "Estimated from OpenStreetMap road data; live traffic isn't included.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Navigator.Preview.None -> Unit
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p is Navigator.Preview.Ready) {
                    Button(
                        onClick = { a.start(p.destination, p.route) },
                        modifier = Modifier.height(56.dp),
                    ) {
                        Icon(Icons.Filled.Navigation, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start", fontSize = 18.sp)
                    }
                }
                FilledTonalButton(onClick = { a.openInGoogleMaps(dest) }, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Filled.Map, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Google Maps")
                }
                SaveMenu(dest, a)
            }
        }
    }
}

@Composable
private fun SaveMenu(dest: Destination, a: MapActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.height(56.dp)) {
            Icon(Icons.Filled.Star, null)
            Spacer(Modifier.width(6.dp))
            Text("Save")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Set as Home") }, leadingIcon = { Icon(Icons.Filled.Home, null) }, onClick = {
                open = false; a.save(PlaceKind.HOME, dest)
            })
            DropdownMenuItem(text = { Text("Set as Work") }, leadingIcon = { Icon(Icons.Filled.Work, null) }, onClick = {
                open = false; a.save(PlaceKind.WORK, dest)
            })
            DropdownMenuItem(text = { Text("Add to favourites") }, leadingIcon = { Icon(Icons.Filled.Star, null) }, onClick = {
                open = false; a.save(PlaceKind.FAVORITE, dest)
            })
        }
    }
}

@Composable
private fun NavBar(nav: NavState, imperial: Boolean, voice: Boolean, a: MapActions) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NavSummary(nav, imperial)
                Text(
                    "to ${nav.destination.name}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = a.toggleVoice, modifier = Modifier.size(56.dp)) {
                Icon(if (voice) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, if (voice) "Mute voice" else "Unmute voice")
            }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = a.stop,
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.Danger, contentColor = Color.White),
                modifier = Modifier.height(56.dp),
            ) { Text("End", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun MapButton(icon: ImageVector, label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(58.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun SpeedBubble(t: DashboardTelemetry, s: DashSettings, modifier: Modifier) {
    val kmh = t.gpsSpeedKmh
    val over = s.speedWarningEnabled && kmh != null && kmh > s.speedWarningKmh
    Surface(
        modifier = modifier.size(88.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(3.dp, if (over) DashColors.Danger else MaterialTheme.colorScheme.outline),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                kmh?.let { s.unit.fromKmh(it).roundToInt().toString() } ?: "--",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (over) DashColors.Danger else MaterialTheme.colorScheme.onSurface,
                style = Tabular,
            )
            Text(s.unit.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
