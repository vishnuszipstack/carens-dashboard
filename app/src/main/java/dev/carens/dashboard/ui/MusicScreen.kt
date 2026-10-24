package dev.carens.dashboard.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.carens.dashboard.info.MediaApp
import dev.carens.dashboard.info.NowPlaying
import dev.carens.dashboard.info.NowPlayingRepository
import kotlinx.coroutines.delay

/** Full music screen: big art, progress, controls, volume, queue and music apps. */
@Composable
fun MusicScreen(np: NowPlaying?, access: Boolean, repo: NowPlayingRepository, onGrantAccess: () -> Unit) {
    var apps by remember { mutableStateOf<List<MediaApp>>(emptyList()) }
    LaunchedEffect(Unit) { apps = runCatching { repo.mediaApps() }.getOrDefault(emptyList()) }

    if (!access) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Equalizer, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("See and control your music here", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Android only lets an app see what Spotify, YouTube Music and others are playing if you allow " +
                    "notification access. Carens Dashboard uses it only for music; notifications are ignored.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGrantAccess, modifier = Modifier.height(56.dp)) { Text("Allow notification access", fontSize = 17.sp) }
            Spacer(Modifier.height(28.dp))
            AppsRow(apps, repo)
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AlbumArt(np, Modifier.fillMaxHeight(0.8f).aspectRatio(1f).align(Alignment.CenterVertically))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    NowPlayingPanel(np, repo, Modifier.weight(1f))
                    AppsRow(apps, repo)
                }
                if (np != null && np.queue.isNotEmpty()) {
                    QueueList(np, repo, Modifier.width(300.dp).fillMaxHeight())
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AlbumArt(np, Modifier.fillMaxWidth(0.75f).aspectRatio(1f).align(Alignment.CenterHorizontally))
                NowPlayingPanel(np, repo, Modifier)
                if (np != null && np.queue.isNotEmpty()) QueueList(np, repo, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                AppsRow(apps, repo)
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(np: NowPlaying?, repo: NowPlayingRepository, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        if (np == null) {
            Text("Nothing playing", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text("Start music in one of your apps below.", color = muted, fontSize = 17.sp)
        } else {
            Text(np.title, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
            np.artist?.let { Text(it, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(listOfNotNull(np.album, np.appName).joinToString(" · "), fontSize = 15.sp, color = muted, maxLines = 1)
            Spacer(Modifier.height(14.dp))
            Progress(np, repo)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconButton(onClick = { repo.previous() }, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(48.dp))
                }
                FilledIconButton(onClick = { repo.playPause() }, modifier = Modifier.size(92.dp)) {
                    Icon(if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (np.isPlaying) "Pause" else "Play", Modifier.size(52.dp))
                }
                IconButton(onClick = { repo.next() }, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Filled.SkipNext, "Next", Modifier.size(48.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { repo.openApp(np.packageName) }) {
                    Icon(Icons.Filled.OpenInNew, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Open ${np.appName}")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        VolumeRow(repo)
    }
}

@Composable
private fun Progress(np: NowPlaying, repo: NowPlayingRepository) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.isPlaying) {
        while (np.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val pos = np.currentPositionMs(now)
    val dur = np.durationMs
    if (dur <= 0) return
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column {
        Slider(
            value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { if (np.canSeek) dragging = it },
            onValueChangeFinished = {
                dragging?.let { repo.seekTo((it * dur).toLong()) }
                dragging = null
            },
            enabled = np.canSeek,
        )
        Row {
            Text(mmss(pos), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(mmss(dur), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun VolumeRow(repo: NowPlayingRepository) {
    val (initial, max) = remember { repo.volume() }
    var level by remember { mutableFloatStateOf(initial.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 520.dp)) {
        Icon(Icons.Filled.VolumeDown, "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = level,
            onValueChange = {
                level = it
                repo.setVolume(it.toInt())
            },
            valueRange = 0f..max.toFloat().coerceAtLeast(1f),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(Icons.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QueueList(np: NowPlaying, repo: NowPlayingRepository, modifier: Modifier) {
    WidgetCard(modifier, padding = 8.dp) {
        WidgetLabel("Up next", Modifier.padding(8.dp))
        LazyColumn {
            items(np.queue, key = { it.id }) { q ->
                val active = q.id == np.activeQueueId
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .then(if (active) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                        .clickable { repo.playQueueItem(q.id) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Text(
                        q.title,
                        fontSize = 16.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    q.subtitle?.let {
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsRow(apps: List<MediaApp>, repo: NowPlayingRepository) {
    if (apps.isEmpty()) return
    Column {
        WidgetLabel("Music apps", Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(apps, key = { it.packageName }) { app ->
                DockIcon(app.icon, app.label, size = 48) { repo.openApp(app.packageName) }
            }
        }
    }
}
