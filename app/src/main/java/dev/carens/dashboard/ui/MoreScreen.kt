package dev.carens.dashboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.carens.dashboard.fuel.FuelEntry
import dev.carens.dashboard.fuel.FuelMath
import dev.carens.dashboard.nav.LatLon
import dev.carens.dashboard.nav.navDistance
import dev.carens.dashboard.places.ParkingSpot
import dev.carens.dashboard.places.PlaceKind
import dev.carens.dashboard.places.SavedPlace
import dev.carens.dashboard.telemetry.distanceMeters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MorePage { HUB, TRIPS, FUEL, PLACES, SETTINGS, CAR }

private data class Tile(val page: MorePage, val icon: ImageVector, val title: String, val subtitle: String)

@Composable
fun MoreHub(
    fuelSummary: String,
    placesCount: Int,
    parking: ParkingSpot?,
    position: LatLon?,
    updateAvailable: Boolean,
    onOpen: (MorePage) -> Unit,
    onFindCar: () -> Unit,
    onParkHere: () -> Unit,
) {
    val tiles = listOf(
        Tile(MorePage.TRIPS, Icons.Filled.QueryStats, "Trips & stats", "Today, week, month, 0–100 times, export"),
        Tile(MorePage.FUEL, Icons.Filled.LocalGasStation, "Fuel log", fuelSummary),
        Tile(MorePage.PLACES, Icons.Filled.Star, "Saved places", if (placesCount == 0) "Home, Work and favourites" else "$placesCount saved"),
        Tile(MorePage.CAR, Icons.Filled.DirectionsCar, "Android Auto preview", "See what the car screen shows"),
        Tile(MorePage.SETTINGS, if (updateAvailable) Icons.Filled.SystemUpdate else Icons.Filled.Settings, "Settings", if (updateAvailable) "Update available" else "Display, units, voice, updates"),
    )
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("More", onBack = null)
        ParkingCard(parking, position, onFindCar, onParkHere, Modifier.padding(horizontal = 16.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(260.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tiles) { tile ->
                WidgetCard(Modifier.fillMaxWidth().height(110.dp), onClick = { onOpen(tile.page) }) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(tile.icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(tile.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text(tile.subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParkingCard(parking: ParkingSpot?, position: LatLon?, onFindCar: () -> Unit, onParkHere: () -> Unit, modifier: Modifier) {
    WidgetCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DirectionsCar, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Find my car", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                val text = if (parking == null) {
                    "Saved automatically when a trip ends."
                } else {
                    val ago = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(parking.savedAtMs))
                    val dist = position?.let { navDistance(distanceMeters(it.lat, it.lon, parking.point.lat, parking.point.lon), false) }
                    "Parked $ago" + (dist?.let { " · $it away" } ?: "")
                }
                Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (parking != null) {
                FilledTonalButton(onClick = onFindCar) { Text("Show") }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = onParkHere) { Text("Park here") }
        }
    }
}

// --- Fuel log ------------------------------------------------------------------

@Composable
fun FuelScreen(entries: List<FuelEntry>, onAdd: (Double, Double, Double, Boolean) -> Unit, onDelete: (Long) -> Unit, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    val summary = remember(entries) { FuelMath.summarize(entries) }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Fuel log", onBack) {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.LocalGasStation, null) },
                text = { Text("Add fill-up") },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                WidgetCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        BigStat(summary.averageKmPerLitre?.let { String.format(Locale.US, "%.1f", it) } ?: "—", "avg km/l", Modifier.weight(1f))
                        BigStat(summary.lastKmPerLitre?.let { String.format(Locale.US, "%.1f", it) } ?: "—", "last km/l", Modifier.weight(1f))
                        BigStat(summary.averageCostPerKm?.let { String.format(Locale.US, "₹%.2f", it) } ?: "—", "per km", Modifier.weight(1f))
                        BigStat(String.format(Locale.US, "₹%,.0f", summary.totalSpent), "total spent", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Mileage uses the full-tank method: fill to full, note the odometer, and the next full fill-up gives km/l.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entries.isEmpty()) {
                item { Text("No fill-ups yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            }
            items(entries, key = { it.id }) { e ->
                val interval = summary.intervals.firstOrNull { it.endEntryId == e.id }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${dateFmt.format(Date(e.timeMs))} · ${String.format(Locale.US, "%,.0f", e.odometerKm)} km" + if (e.fullTank) " · full" else " · partial",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                String.format(Locale.US, "%.2f L · ₹%,.0f · ₹%.2f/L", e.litres, e.totalCost, e.pricePerLitre),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            interval?.let {
                                Text(
                                    String.format(Locale.US, "%.1f km/l over %,.0f km", it.kmPerLitre, it.km),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        IconButton(onClick = { onDelete(e.id) }) { Icon(Icons.Filled.Delete, "Delete fill-up") }
                    }
                }
            }
        }
    }
    if (adding) {
        AddFuelDialog(
            lastOdometer = entries.maxOfOrNull { it.odometerKm },
            onDismiss = { adding = false },
            onSave = { odo, l, cost, full -> adding = false; onAdd(odo, l, cost, full) },
        )
    }
}

@Composable
private fun AddFuelDialog(lastOdometer: Double?, onDismiss: () -> Unit, onSave: (Double, Double, Double, Boolean) -> Unit) {
    var odo by rememberSaveable { mutableStateOf("") }
    var litres by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var full by rememberSaveable { mutableStateOf(true) }
    val o = odo.toDoubleOrNull()
    val l = litres.toDoubleOrNull()
    val c = cost.toDoubleOrNull()
    val odoError = o != null && lastOdometer != null && o <= lastOdometer
    val valid = o != null && l != null && c != null && l > 0 && c >= 0 && !odoError
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add fill-up") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    odo, { odo = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Odometer (km)") },
                    supportingText = {
                        if (odoError) Text("Must be more than the last reading (${lastOdometer?.toLong()} km)")
                        else lastOdometer?.let { Text("Last: ${it.toLong()} km") }
                    },
                    isError = odoError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    litres, { litres = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Litres") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    cost, { cost = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Total paid (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Filled to full", Modifier.weight(1f))
                    Switch(full, { full = it })
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(o!!, l!!, c!!, full) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Saved places -------------------------------------------------------------------

@Composable
fun PlacesScreen(places: List<SavedPlace>, onGo: (SavedPlace) -> Unit, onRemove: (Long) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Saved places", onBack)
        if (places.isEmpty()) {
            Text(
                "Search for a place on the Map, then tap Save to set it as Home, Work or a favourite. " +
                    "You can also long-press the map to drop a pin.",
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(places.sortedBy { it.kind.ordinal }, key = { it.id }) { p ->
                WidgetCard(Modifier.fillMaxWidth(), onClick = { onGo(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (p.kind) {
                                PlaceKind.HOME -> Icons.Filled.Home
                                PlaceKind.WORK -> Icons.Filled.Work
                                PlaceKind.FAVORITE -> Icons.Filled.Star
                            },
                            null,
                            Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            p.address?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        IconButton(onClick = { onGo(p) }) { Icon(Icons.Filled.Navigation, "Navigate") }
                        IconButton(onClick = { onRemove(p.id) }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
            }
        }
    }
}
