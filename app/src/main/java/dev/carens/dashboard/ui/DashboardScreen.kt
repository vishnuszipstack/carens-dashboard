package dev.carens.dashboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.settings.SpeedUnit
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.GpsQuality
import dev.carens.dashboard.telemetry.VehicleReading
import dev.carens.dashboard.info.NowPlaying
import dev.carens.dashboard.info.PlaceInfo
import dev.carens.dashboard.info.WeatherInfo
import kotlin.math.min
import kotlin.math.roundToInt

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** Everything on the dashboard that isn't GPS telemetry. */
data class DashExtras(
    val weather: WeatherInfo? = null,
    val place: PlaceInfo? = null,
    val nowPlaying: NowPlaying? = null,
    val musicAccess: Boolean = false,
    val dark: Boolean = true,
)

class MusicActions(
    val grantAccess: () -> Unit,
    val previous: () -> Unit,
    val playPause: () -> Unit,
    val next: () -> Unit,
)

@Composable
fun DashboardScreen(
    telemetry: DashboardTelemetry,
    settings: DashSettings,
    extras: DashExtras,
    music: MusicActions,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    onNavigate: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var confirmEnd by remember { mutableStateOf(false) }
    val tripActive = telemetry.trip != null
    val pad = if (settings.fullScreen) Modifier.padding(10.dp) else Modifier.safeDrawingPadding().padding(10.dp)

    BoxWithConstraints(Modifier.fillMaxSize().then(pad)) {
        val landscape = maxWidth > maxHeight
        val actions = @Composable {
            ActionBar(
                tripActive = tripActive,
                onTrip = { if (tripActive) confirmEnd = true else onStartTrip() },
                onNavigate = onNavigate,
                onOpenTrips = onOpenTrips,
                onOpenSettings = onOpenSettings,
            )
        }
        val map = @Composable { m: Modifier ->
            MapCard(
                lat = telemetry.latitude,
                lon = telemetry.longitude,
                bearing = telemetry.bearingDegrees,
                dark = extras.dark,
                place = extras.place,
                modifier = m,
            )
        }
        val weather = @Composable { m: Modifier -> WeatherCard(extras.weather, m) }
        val musicCard = @Composable { m: Modifier ->
            MusicCard(
                nowPlaying = extras.nowPlaying,
                accessGranted = extras.musicAccess,
                onGrantAccess = music.grantAccess,
                onPrevious = music.previous,
                onPlayPause = music.playPause,
                onNext = music.next,
                modifier = m,
            )
        }

        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(0.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopStrip(telemetry, settings)
                    SpeedHero(telemetry, settings, Modifier.weight(1f))
                    StatsGrid(telemetry, settings, columns = 3, fill = false)
                    if (settings.showEcuSlots) EcuStrip(telemetry)
                }
                Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (settings.showMap) {
                        map(Modifier.weight(1f).fillMaxWidth())
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        weather(Modifier.weight(1f).fillMaxHeight())
                        musicCard(Modifier.weight(1.5f).fillMaxHeight())
                    }
                    actions()
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TopStrip(telemetry, settings)
                SpeedHero(telemetry, settings, Modifier.weight(if (settings.showMap) 0.8f else 1f))
                StatsGrid(telemetry, settings, columns = 3, fill = false)
                if (settings.showEcuSlots) EcuStrip(telemetry)
                if (settings.showMap) map(Modifier.weight(1f).fillMaxWidth())
                weather(Modifier.fillMaxWidth().height(80.dp))
                musicCard(Modifier.fillMaxWidth().height(80.dp))
                actions()
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this trip?") },
            text = { Text("It will be saved to trip history.") },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; onStopTrip() }) { Text("End trip") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }) { Text("Keep going") }
            },
        )
    }
}

@Composable
private fun TopStrip(telemetry: DashboardTelemetry, settings: DashSettings) {
    val (clock, date) = rememberClock()
    val title = settings.vehicle.nickname.ifBlank { "Kia Carens" }.uppercase()
    Row(
        Modifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        telemetry.bearingDegrees?.let {
            Text(
                Format.compass(it),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
        }
        GpsBadge(telemetry.gpsQuality)
        Spacer(Modifier.width(14.dp))
        Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(clock, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
    }
}

@Composable
private fun GpsBadge(quality: GpsQuality) {
    val (icon, color, label) = when (quality) {
        GpsQuality.GOOD -> Triple(Icons.Filled.GpsFixed, MaterialTheme.colorScheme.primary, "GPS")
        GpsQuality.FAIR -> Triple(Icons.Filled.GpsFixed, MaterialTheme.colorScheme.onSurfaceVariant, "GPS")
        GpsQuality.POOR -> Triple(Icons.Filled.GpsNotFixed, DashColors.Warning, "WEAK")
        GpsQuality.NONE -> Triple(Icons.Filled.GpsOff, DashColors.Warning, "NO GPS")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun SpeedHero(telemetry: DashboardTelemetry, settings: DashSettings, modifier: Modifier = Modifier) {
    val unit = settings.unit
    val kmh = telemetry.gpsSpeedKmh
    val shown = kmh?.let { unit.fromKmh(it) }
    val over = settings.speedWarningEnabled && kmh != null && kmh > settings.speedWarningKmh
    val scheme = MaterialTheme.colorScheme
    val numberColor = when {
        kmh == null -> scheme.onSurfaceVariant
        over -> DashColors.Danger
        else -> scheme.onBackground
    }
    val arcColor = if (over) DashColors.Danger else scheme.primary
    val trackColor = scheme.outlineVariant
    val fraction = ((shown ?: 0.0) / unit.gaugeMax).coerceIn(0.0, 1.0).toFloat()

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // A 240° arc is 0.75 × its diameter tall.
        val diameterDp = min(maxWidth.value, maxHeight.value / 0.75f) * 0.95f
        val fontScale = LocalDensity.current.fontScale
        val numberSp = (diameterDp * 0.30f / fontScale).sp

        Canvas(Modifier.fillMaxSize()) {
            val d = min(size.width, size.height / 0.75f) * 0.95f
            val stroke = d * 0.035f
            val topLeft = Offset((size.width - d) / 2f, (size.height - 0.75f * d) / 2f)
            val arcSize = Size(d, d)
            drawArc(trackColor, 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction > 0f) {
                drawArc(arcColor, 150f, 240f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = shown?.roundToInt()?.toString() ?: "--",
                color = numberColor,
                fontSize = numberSp,
                lineHeight = numberSp,
                fontWeight = FontWeight.Medium,
                style = Tabular,
            )
            Text(unit.label, fontSize = 18.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            StatusLine(telemetry, settings, over)
        }
    }
}

@Composable
private fun StatusLine(telemetry: DashboardTelemetry, settings: DashSettings, over: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val acc = telemetry.gpsAccuracyMeters?.roundToInt()
    val (text, color) = when {
        telemetry.locationError != null -> "Location unavailable" to DashColors.Warning
        !telemetry.serviceRunning || !telemetry.hasFix -> "Waiting for GPS…" to muted
        telemetry.isStale -> "GPS signal lost — speed hidden" to DashColors.Warning
        over -> "Above ${Format.speed(settings.unit.fromKmh(settings.speedWarningKmh.toDouble()))} ${settings.unit.label}" to DashColors.Danger
        telemetry.gpsQuality == GpsQuality.POOR -> "Weak GPS${acc?.let { " · ±$it m" } ?: ""}" to DashColors.Warning
        else -> "GPS speed${acc?.let { " · ±$it m" } ?: ""}" to muted
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}

private data class Stat(val label: String, val value: String, val unit: String)

@Composable
private fun StatsGrid(
    telemetry: DashboardTelemetry,
    settings: DashSettings,
    columns: Int,
    fill: Boolean,
    modifier: Modifier = Modifier,
) {
    val u: SpeedUnit = settings.unit
    val trip = telemetry.trip
    val dash = "—"
    val duration = trip?.let { Format.durationParts(it.elapsedMs) }
    val moving = trip?.let { Format.durationParts(it.movingMs) }
    val elevation = telemetry.altitudeMeters?.let { Format.elevation(it, u) }
    val stats = listOf(
        Stat("TRIP", trip?.let { Format.distance(it.distanceM, u) } ?: dash, u.distanceLabel),
        Stat("DURATION", duration?.first ?: dash, duration?.second ?: ""),
        Stat("MOVING", moving?.first ?: dash, moving?.second ?: ""),
        Stat("AVG SPEED", trip?.avgMovingKmh?.let { Format.speed(u.fromKmh(it)) } ?: dash, u.label),
        Stat(
            "MAX SPEED",
            trip?.maxSpeedKmh?.takeIf { it > 0 }?.let { Format.speed(u.fromKmh(it)) } ?: dash,
            u.label,
        ),
        Stat("ELEVATION", elevation?.let { "≈${it.first}" } ?: "n/a", elevation?.second ?: ""),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f) else Modifier.height(74.dp)),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { StatTile(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun StatTile(stat: Stat, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(
                stat.label,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    stat.value,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = Tabular,
                    maxLines = 1,
                )
                if (stat.unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stat.unit,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Reserved ECU slots. Only real readings with a source are ever shown. */
@Composable
private fun EcuStrip(telemetry: DashboardTelemetry) {
    val v = telemetry.vehicle
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EcuTile("RPM", v.rpm, Modifier.weight(1f).fillMaxHeight())
            EcuTile("COOLANT", v.coolantTempC, Modifier.weight(1f).fillMaxHeight())
            EcuTile("FUEL", v.fuelLevelPct, Modifier.weight(1f).fillMaxHeight())
        }
        Text(
            "ECU data unavailable — no vehicle data source connected",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EcuTile(label: String, reading: VehicleReading<*>?, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                reading?.let { "${it.value} ${it.unit}" } ?: "Not available",
                style = MaterialTheme.typography.bodyMedium,
                color = if (reading == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ActionBar(
    tripActive: Boolean,
    onTrip: () -> Unit,
    onNavigate: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val h = 60.dp
    Row(Modifier.fillMaxWidth().height(h), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tripActive) {
            OutlinedButton(
                onClick = onTrip,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DashColors.Danger),
                border = BorderStroke(1.5.dp, DashColors.Danger),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("End trip", fontSize = 17.sp)
            }
        } else {
            Button(onClick = onTrip, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Start trip", fontSize = 17.sp)
            }
        }
        FilledTonalButton(onClick = onNavigate, modifier = Modifier.weight(1f).fillMaxHeight()) {
            Icon(Icons.Filled.Navigation, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Maps", fontSize = 17.sp)
        }
        FilledTonalIconButton(onClick = onOpenTrips, modifier = Modifier.size(h)) {
            Icon(Icons.Filled.History, contentDescription = "Trip history")
        }
        FilledTonalIconButton(onClick = onOpenSettings, modifier = Modifier.size(h)) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}
