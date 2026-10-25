package dev.carens.dashboard.ui

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RampLeft
import androidx.compose.material.icons.filled.RampRight
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.carens.dashboard.info.NowPlaying
import dev.carens.dashboard.info.WeatherInfo
import dev.carens.dashboard.nav.Maneuver
import dev.carens.dashboard.nav.NavState
import dev.carens.dashboard.nav.navDistance
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.GpsQuality
import java.util.Date
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** Rounded tile used for every home-screen widget. */
@Composable
fun WidgetCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier.clip(shape).then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun WidgetLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

// --- Speed ---------------------------------------------------------------------

/**
 * Speedometer: a 270° ring that shades green → amber → red as speed rises,
 * with tick marks every 20 (km/h or mph) and a big tabular number.
 */
@Composable
fun SpeedGauge(telemetry: DashboardTelemetry, settings: DashSettings, modifier: Modifier = Modifier) {
    val unit = settings.unit
    val kmh = telemetry.gpsSpeedKmh
    val shown = kmh?.let { unit.fromKmh(it) }
    val over = settings.speedWarningEnabled && kmh != null && kmh > settings.speedWarningKmh
    val scheme = MaterialTheme.colorScheme
    val numberColor = when {
        kmh == null -> scheme.onSurfaceVariant
        over -> DashColors.Danger
        else -> scheme.onSurface
    }
    val trackColor = scheme.surfaceVariant
    val tickColor = scheme.outline
    val fraction = ((shown ?: 0.0) / unit.gaugeMax).coerceIn(0.0, 1.0).toFloat()
    val sweep = 270f

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val d = min(maxWidth.value, maxHeight.value) * 0.92f
        val fontScale = LocalDensity.current.fontScale
        val numberSp = (d * 0.30f / fontScale).sp

        Canvas(Modifier.size(d.dp)) {
            val stroke = size.minDimension * 0.075f
            val inset = stroke / 2 + size.minDimension * 0.06f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(trackColor, 135f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction > 0f) {
                val brush = if (over) {
                    androidx.compose.ui.graphics.SolidColor(DashColors.Danger)
                } else {
                    // Sweep gradient starts at 3 o'clock; rotate so it begins where the arc does.
                    androidx.compose.ui.graphics.Brush.sweepGradient(
                        0f to DashColors.Accent,
                        0.45f to DashColors.Accent,
                        0.62f to DashColors.Warning,
                        0.75f to DashColors.Danger,
                        0.9f to DashColors.Danger,
                        // Back to green just before 360° so the start cap (which pokes
                        // slightly "behind" 0°) isn't tinted red.
                        0.97f to DashColors.Accent,
                        1f to DashColors.Accent,
                        center = center,
                    )
                }
                rotate(135f) {
                    drawArc(brush, 0f, sweep * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            // Ticks every 20 units on the outside of the ring.
            val step = 20.0
            var v = 0.0
            val r1 = size.minDimension / 2 - size.minDimension * 0.015f
            val r0 = r1 - size.minDimension * 0.035f
            while (v <= unit.gaugeMax + 0.1) {
                val ang = Math.toRadians(135.0 + sweep * (v / unit.gaugeMax))
                val c = kotlin.math.cos(ang).toFloat()
                val s = kotlin.math.sin(ang).toFloat()
                drawLine(
                    tickColor,
                    Offset(center.x + c * r0, center.y + s * r0),
                    Offset(center.x + c * r1, center.y + s * r1),
                    strokeWidth = size.minDimension * 0.008f,
                    cap = StrokeCap.Round,
                )
                v += step
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = shown?.roundToInt()?.toString() ?: "--",
                color = numberColor,
                fontSize = numberSp,
                lineHeight = numberSp,
                fontWeight = FontWeight.Bold,
                style = Tabular,
            )
            Text(unit.label, fontSize = 16.sp, color = scheme.onSurfaceVariant, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            GpsStatus(telemetry, settings, over)
        }
    }
}

@Composable
private fun GpsStatus(t: DashboardTelemetry, settings: DashSettings, over: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, color) = when {
        t.locationError != null -> "Location unavailable" to DashColors.Warning
        !t.serviceRunning || !t.hasFix -> "Waiting for GPS…" to muted
        t.isStale -> "GPS lost" to DashColors.Warning
        over -> "Above ${Format.speed(settings.unit.fromKmh(settings.speedWarningKmh.toDouble()))} ${settings.unit.label}" to DashColors.Danger
        t.gpsQuality == GpsQuality.POOR -> "Weak GPS" to DashColors.Warning
        else -> (t.bearingDegrees?.let { "Heading ${Format.compass(it)}" } ?: "GPS speed") to muted
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun GpsBadge(quality: GpsQuality) {
    val (icon, color) = when (quality) {
        GpsQuality.GOOD -> Icons.Filled.GpsFixed to MaterialTheme.colorScheme.primary
        GpsQuality.FAIR -> Icons.Filled.GpsFixed to MaterialTheme.colorScheme.onSurfaceVariant
        GpsQuality.POOR -> Icons.Filled.GpsNotFixed to DashColors.Warning
        GpsQuality.NONE -> Icons.Filled.GpsOff to DashColors.Warning
    }
    Icon(icon, contentDescription = "GPS ${quality.name.lowercase()}", tint = color, modifier = Modifier.size(20.dp))
}

// --- Clock & weather -------------------------------------------------------------

@Composable
fun rememberClock(): Pair<String, String> {
    val context = LocalContext.current
    val state = produceState(initialValue = "" to "") {
        val time = DateFormat.getTimeFormat(context)
        val day = java.text.SimpleDateFormat("EEEE, d MMM", java.util.Locale.getDefault())
        while (true) {
            val now = Date()
            value = time.format(now) to day.format(now)
            delay(5_000)
        }
    }
    return state.value
}

@Composable
fun ClockWidget(placeLabel: String?, modifier: Modifier = Modifier) {
    val (time, date) = rememberClock()
    WidgetCard(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Scale the time to the card so "10:45 PM" never wraps.
        val timeSp = (maxWidth.value / (time.length.coerceAtLeast(5) * 0.6f)).coerceIn(26f, 56f).sp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(time, fontSize = timeSp, fontWeight = FontWeight.SemiBold, style = Tabular, lineHeight = timeSp, maxLines = 1)
            Text(date, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (placeLabel != null) {
                Spacer(Modifier.height(4.dp))
                Text("📍 $placeLabel", fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        }
    }
}

@Composable
fun WeatherWidget(weather: WeatherInfo?, modifier: Modifier = Modifier) {
    WidgetCard(modifier) {
        if (weather == null) {
            WidgetLabel("Weather")
            Spacer(Modifier.height(6.dp))
            Text("Shows once there's a GPS fix and internet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(weather.emoji, fontSize = 36.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(weather.tempLabel, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp)
                    Text(weather.description, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val extra = listOfNotNull(
                        weather.feelsLikeC?.let { "feels ${it.roundToInt()}°" },
                        weather.humidityPct?.let { "$it%" },
                    ).joinToString(" · ")
                    if (extra.isNotEmpty()) Text(extra, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// --- Music -------------------------------------------------------------------------

class MusicActions(
    val grantAccess: () -> Unit,
    val previous: () -> Unit,
    val playPause: () -> Unit,
    val next: () -> Unit,
)

@Composable
fun AlbumArt(np: NowPlaying?, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val art = np?.art
        if (art != null) {
            Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
fun MusicWidget(
    np: NowPlaying?,
    access: Boolean,
    actions: MusicActions,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    WidgetCard(modifier, onClick = onOpen, padding = 10.dp) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(np, Modifier.fillMaxHeight().aspectRatio(1f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when {
                    !access -> {
                        Text("Music", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = actions.grantAccess, contentPadding = PaddingValues(0.dp)) {
                            Text("Allow notification access to show songs")
                        }
                    }
                    np == null -> {
                        Text("Nothing playing", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("Tap to pick a music app", fontSize = 14.sp, color = muted)
                    }
                    else -> {
                        Text(np.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(np.artist, np.appName).joinToString(" · "),
                            fontSize = 14.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (access && np != null) {
                IconButton(onClick = actions.previous, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", modifier = Modifier.size(30.dp))
                }
                FilledIconButton(onClick = actions.playPause, modifier = Modifier.size(60.dp)) {
                    Icon(if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (np.isPlaying) "Pause" else "Play", modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = actions.next, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipNext, "Next", modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

// --- Navigation ------------------------------------------------------------------

fun maneuverIcon(m: Maneuver): ImageVector = when (m) {
    Maneuver.DEPART, Maneuver.STRAIGHT -> Icons.Filled.Straight
    Maneuver.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    Maneuver.LEFT -> Icons.Filled.TurnLeft
    Maneuver.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    Maneuver.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    Maneuver.RIGHT -> Icons.Filled.TurnRight
    Maneuver.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    Maneuver.UTURN -> Icons.Filled.UTurnLeft
    Maneuver.ROUNDABOUT -> Icons.Filled.RoundaboutRight
    Maneuver.MERGE -> Icons.Filled.Merge
    Maneuver.RAMP_LEFT -> Icons.Filled.RampLeft
    Maneuver.RAMP_RIGHT -> Icons.Filled.RampRight
    Maneuver.FORK_LEFT -> Icons.Filled.ForkLeft
    Maneuver.FORK_RIGHT -> Icons.Filled.ForkRight
    Maneuver.ARRIVE -> Icons.Filled.Flag
}

/** Big green next-turn banner, Google-Maps style. */
@Composable
fun NavBanner(nav: NavState, imperial: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val green = Color(0xFF0F7B4A)
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = green, contentColor = Color.White) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val step = nav.nextStep
                Icon(
                    if (nav.arrived) Icons.Filled.Flag else maneuverIcon(step.maneuver),
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 44.dp else 60.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        nav.arrived -> Text("You've arrived", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        nav.rerouting -> Text("Rerouting…", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        else -> {
                            Text(
                                navDistance(nav.distanceToNextM, imperial),
                                fontSize = if (compact) 26.sp else 34.sp,
                                fontWeight = FontWeight.Bold,
                                style = Tabular,
                            )
                            Text(
                                step.instruction,
                                fontSize = if (compact) 16.sp else 19.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            val then = nav.stepAfter
            if (!compact && then != null && !nav.arrived && nav.distanceToNextM < 1500) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.background(Color(0x33000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Then", fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Icon(maneuverIcon(then.maneuver), null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** ETA, time and distance left. */
@Composable
fun NavSummary(nav: NavState, imperial: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val eta = DateFormat.getTimeFormat(context).format(Date(nav.etaWallMs))
    // Wraps the time-left part onto its own line when space is tight, instead of
    // squeezing it into a one-word-per-line column.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(eta, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = Tabular, maxLines = 1)
        Text(
            "${Format.duration((nav.remainingS * 1000).toLong())} · ${navDistance(nav.remainingM, imperial)}",
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// --- Small round action button -------------------------------------------------------

@Composable
fun RoundAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(58.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = tint),
        ) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
