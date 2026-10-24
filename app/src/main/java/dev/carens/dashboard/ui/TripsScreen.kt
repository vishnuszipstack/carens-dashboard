package dev.carens.dashboard.ui

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.carens.dashboard.MainViewModel
import dev.carens.dashboard.settings.SpeedUnit
import dev.carens.dashboard.trips.TripEntity
import dev.carens.dashboard.trips.avgMovingKmh
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripsScreen(vm: MainViewModel, unit: SpeedUnit, onBack: () -> Unit) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    var confirmDeleteAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader("Trip history", onBack) {
            if (trips.isNotEmpty()) {
                IconButton(onClick = { confirmDeleteAll = true }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete all trips")
                }
            }
        }
        if (trips.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No trips yet. Start one from the dashboard, or just drive with auto trips on.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val totalM = trips.sumOf { it.distanceM }
            Text(
                "${trips.size} trips · ${Format.distance(totalM, unit)} ${unit.distanceLabel} total",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(trips, key = { it.id }) { trip ->
                    TripRow(trip, unit, onDelete = { vm.deleteTrip(trip.id) })
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all trips?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteAll = false; vm.deleteAllTrips() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TripRow(trip: TripEntity, unit: SpeedUnit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val timeFmt = remember(context) { DateFormat.getTimeFormat(context) }
    val dayFmt = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    val start = Date(trip.startedAtWallMs)
    val end = trip.endedAtWallMs?.let { Date(it) }

    val parts = buildList {
        add(Format.duration(trip.durationMs))
        trip.avgMovingKmh()?.let { add("avg ${Format.speed(unit.fromKmh(it))} ${unit.label}") }
        if (trip.maxSpeedKmh > 0) add("max ${Format.speed(unit.fromKmh(trip.maxSpeedKmh))} ${unit.label}")
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(dayFmt.format(start)).append(" · ").append(timeFmt.format(start))
                        if (end != null) append(" – ").append(timeFmt.format(end))
                        if (trip.autoStarted) append(" · auto")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${Format.distance(trip.distanceM, unit)} ${unit.distanceLabel}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                if (trip.gpsGapMs >= 60_000) {
                    Text(
                        "GPS was lost for ${Format.duration(trip.gpsGapMs)}; distance may be under-counted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DashColors.Warning,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete trip")
            }
        }
    }
}
