package dev.carens.dashboard.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.carens.dashboard.car.CarView
import dev.carens.dashboard.car.GaugeArt
import dev.carens.dashboard.info.MapTiles
import dev.carens.dashboard.info.NowPlaying
import dev.carens.dashboard.info.PlaceInfo
import dev.carens.dashboard.info.WeatherInfo
import dev.carens.dashboard.location.LocationPermissions
import dev.carens.dashboard.nav.NavState
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.telemetry.DashboardTelemetry
import dev.carens.dashboard.telemetry.distanceMeters

/**
 * Shows, on the phone, roughly what the Android Auto screen shows: the same
 * picture and text the car gets, in a mock of Android Auto's media layout.
 */
@Composable
fun CarPreviewScreen(
    t: DashboardTelemetry,
    s: DashSettings,
    weather: WeatherInfo?,
    place: PlaceInfo?,
    music: NowPlaying?,
    nav: NavState?,
    tiles: MapTiles,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var map by remember { mutableStateOf<Bitmap?>(null) }
    var mapAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val lat = t.latitude
    val lon = t.longitude
    LaunchedEffect(lat, lon) {
        if (lat != null && lon != null) {
            val last = mapAt
            if (last == null || distanceMeters(last.first, last.second, lat, lon) > 25) {
                tiles.render(lat, lon, zoom = 15, size = 256, dark = true)?.let {
                    map = it
                    mapAt = lat to lon
                }
            }
        }
    }
    val content = CarView.build(context, t, s, weather, place, music, nav, map, LocationPermissions.hasPrecise(context))
    val art = remember(content.artKey) { GaugeArt.render(content.model).asImageBitmap() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Android Auto preview", onBack)
        Text(
            "This is what the car screen shows when you open Carens Dashboard in Android Auto (layout approximated).",
            Modifier.padding(horizontal = 20.dp),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // Mock of Android Auto's media card.
        Box(
            Modifier.padding(16.dp).fillMaxWidth().widthIn(max = 900.dp).aspectRatio(2.2f)
                .clip(RoundedCornerShape(26.dp)).background(Color(0xFF3A3D42)),
        ) {
            Row(Modifier.fillMaxSize().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Spacer(Modifier.height(4.dp))
                    Column {
                        Text(content.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(content.subtitle, color = Color(0xFFDDDDDD), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (music != null) Icon(Icons.Filled.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Box(Modifier.width(76.dp).height(44.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Pause, null, tint = Color(0xFF3A3D42))
                        }
                        if (music != null) Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Icon(if (t.trip == null) Icons.Filled.Flag else Icons.Filled.Stop, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Image(art, "Car screen picture", Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
            }
        }
    }
}
