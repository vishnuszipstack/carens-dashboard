package dev.carens.dashboard.ui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.carens.dashboard.R
import dev.carens.dashboard.info.NowPlaying
import dev.carens.dashboard.info.PlaceInfo
import dev.carens.dashboard.info.WeatherInfo
import java.util.Date
import kotlin.math.roundToInt
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/** Inverted, slightly dimmed tiles for night driving. */
private val NightMapFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -0.85f, 0f, 0f, 0f, 230f,
            0f, -0.85f, 0f, 0f, 235f,
            0f, 0f, -0.85f, 0f, 245f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@Composable
private fun CardSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/**
 * OpenStreetMap that follows the car, heading-arrow in the middle. Panning
 * pauses following for 15 s; the locate button snaps back immediately.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapCard(
    lat: Double?,
    lon: Double?,
    bearing: Float?,
    dark: Boolean,
    place: PlaceInfo?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lastTouchMs by remember { mutableLongStateOf(0L) }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            controller.setZoom(16.5)
            setOnTouchListener { _, _ ->
                lastTouchMs = SystemClock.elapsedRealtime()
                false
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    val following = SystemClock.elapsedRealtime() - lastTouchMs > 15_000

    CardSurface(modifier) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { mv ->
                    mv.overlayManager.tilesOverlay.setColorFilter(if (dark) NightMapFilter else null)
                    if (lat != null && lon != null && following) {
                        mv.controller.animateTo(GeoPoint(lat, lon))
                    }
                },
            )
            if (lat == null || lon == null) {
                Text(
                    "Waiting for location…",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (following) {
                Image(
                    painter = painterResource(R.drawable.ic_nav_arrow),
                    contentDescription = "Your position",
                    modifier = Modifier.align(Alignment.Center).size(34.dp).rotate(bearing ?: 0f),
                )
            }
            place?.let {
                Text(
                    it.label,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xCC05070A), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!following && lat != null && lon != null) {
                FilledTonalIconButton(
                    onClick = {
                        lastTouchMs = 0L
                        mapView.controller.animateTo(GeoPoint(lat, lon))
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(48.dp),
                ) { Icon(Icons.Filled.MyLocation, contentDescription = "Follow my position") }
            }
            Text(
                "© OpenStreetMap contributors",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color(0x99FFFFFF))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color(0xFF333333),
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
fun WeatherCard(weather: WeatherInfo?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    CardSurface(modifier) {
        if (weather == null) {
            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.CenterStart) {
                Text("Weather appears once there's a GPS fix and internet", color = muted, fontSize = 13.sp)
            }
        } else {
            WeatherContent(weather)
        }
    }
}

@Composable
private fun WeatherContent(weather: WeatherInfo) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    run {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(weather.emoji, fontSize = 34.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(weather.tempLabel, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        weather.description,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                val details = buildList {
                    weather.feelsLikeC?.let { add("feels ${it.roundToInt()}°") }
                    weather.windKmh?.let { add("wind ${it.roundToInt()} km/h") }
                    weather.humidityPct?.let { add("$it%") }
                }.joinToString(" · ")
                Text(details, fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Open-Meteo · ${DateFormat.getTimeFormat(context).format(Date(weather.fetchedAtMs))}",
                    fontSize = 11.sp,
                    color = muted,
                )
            }
        }
    }
}

@Composable
fun MusicCard(
    nowPlaying: NowPlaying?,
    accessGranted: Boolean,
    onGrantAccess: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    CardSurface(modifier) {
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = nowPlaying?.art
            Box(
                Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    Image(
                        art.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = muted)
                }
            }
            Spacer(Modifier.width(10.dp))
            when {
                !accessGranted -> Column(Modifier.weight(1f)) {
                    Text("Show what's playing", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    TextButton(onClick = onGrantAccess, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("Allow notification access")
                    }
                }
                nowPlaying == null -> Text("Nothing playing", color = muted, modifier = Modifier.weight(1f))
                else -> {
                    Column(Modifier.weight(1f)) {
                        Text(
                            nowPlaying.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(nowPlaying.artist, nowPlaying.appName).joinToString(" · "),
                            fontSize = 13.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }
                    FilledTonalIconButton(onClick = onPlayPause, modifier = Modifier.size(52.dp)) {
                        Icon(
                            if (nowPlaying.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (nowPlaying.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                }
            }
        }
    }
}

/** Compact clock + date for the top strip. */
@Composable
fun rememberClock(): Pair<String, String> {
    val context = LocalContext.current
    val state = androidx.compose.runtime.produceState(initialValue = "" to "") {
        val time = DateFormat.getTimeFormat(context)
        val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
        while (true) {
            val now = Date()
            value = time.format(now) to day.format(now)
            kotlinx.coroutines.delay(5_000)
        }
    }
    return state.value
}
