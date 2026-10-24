package dev.carens.dashboard.ui

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.carens.dashboard.telemetry.TelemetryHub
import dev.carens.dashboard.trips.TripEntity
import dev.carens.dashboard.trips.avgMovingKmh
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Period(val label: String) { TODAY("Today"), WEEK("This week"), MONTH("This month"), ALL("All time") }

private fun periodStart(p: Period): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return when (p) {
        Period.TODAY -> c.timeInMillis
        Period.WEEK -> { c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek); c.timeInMillis }
        Period.MONTH -> { c.set(Calendar.DAY_OF_MONTH, 1); c.timeInMillis }
        Period.ALL -> 0L
    }
}

/** Stats by period, best 0–60/0–100 times, export, and the trip list. */
@Composable
fun TripsScreen(vm: MainViewModel, unit: SpeedUnit, onBack: (() -> Unit)?) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lastAccel by TelemetryHub.lastAccel.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val inPeriod = remember(trips, period) { trips.filter { it.startedAtWallMs >= periodStart(period) } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Trips & stats", onBack) {
            if (trips.isNotEmpty()) {
                IconButton(onClick = { vm.exportTrips() }) { Icon(Icons.Filled.IosShare, "Export trips as CSV") }
                IconButton(onClick = { confirmDeleteAll = true }) { Icon(Icons.Filled.DeleteSweep, "Delete all trips") }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Period.entries.forEach { p ->
                        FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.label, fontSize = 15.sp) })
                    }
                }
            }
            item { PeriodStats(inPeriod, unit) }
            item { AccelCard(settings.best060Ms, settings.best0100Ms, lastAccel) }
            if (trips.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No trips yet. Start one from Home, or just drive with automatic trips on.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item { WidgetLabel("History", Modifier.padding(top = 6.dp)) }
                items(trips, key = { it.id }) { trip -> TripRow(trip, unit, onDelete = { vm.deleteTrip(trip.id) }) }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all trips?") },
            text = { Text("This can't be undone. Export them first if you want a copy.") },
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
private fun PeriodStats(trips: List<TripEntity>, u: SpeedUnit) {
    val distance = trips.sumOf { it.distanceM }
    val moving = trips.sumOf { it.movingMs }
    val duration = trips.sumOf { it.durationMs }
    val max = trips.maxOfOrNull { it.maxSpeedKmh } ?: 0.0
    val avg = if (moving > 10_000) distance / (moving / 1000.0) * 3.6 else null
    WidgetCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            BigStat(Format.distance(distance, u), u.distanceLabel, Modifier.weight(1f))
            BigStat(trips.size.toString(), if (trips.size == 1) "trip" else "trips", Modifier.weight(1f))
            val d = Format.durationParts(duration)
            BigStat(d.first, "${d.second} driving", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            val m = Format.durationParts(moving)
            BigStat(m.first, "${m.second} moving", Modifier.weight(1f))
            BigStat(avg?.let { Format.speed(u.fromKmh(it)) } ?: "—", "avg ${u.label}", Modifier.weight(1f))
            BigStat(if (max > 0) Format.speed(u.fromKmh(max)) else "—", "max ${u.label}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun AccelCard(best60: Long, best100: Long, last: Map<Int, Long>) {
    fun s(ms: Long) = if (ms > 0) String.format(Locale.US, "%.1f s", ms / 1000.0) else "—"
    WidgetCard(Modifier.fillMaxWidth()) {
        WidgetLabel("Acceleration (approx., from GPS)")
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            BigStat(s(best60), "best 0–60 km/h", Modifier.weight(1f))
            BigStat(s(best100), "best 0–100 km/h", Modifier.weight(1f))
            BigStat(s(last[100] ?: last[60] ?: 0), if (last.containsKey(100)) "last 0–100" else "last 0–60", Modifier.weight(1f))
        }
        Text(
            "Measured automatically when you pull away from a stop. GPS updates once a second, so treat these as rough.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        shape = RoundedCornerShape(16.dp),
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
                Text("${Format.distance(trip.distanceM, unit)} ${unit.distanceLabel}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                if (trip.gpsGapMs >= 60_000) {
                    Text(
                        "GPS was lost for ${Format.duration(trip.gpsGapMs)}; distance may be under-counted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DashColors.Warning,
                    )
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete trip") }
        }
    }
}
