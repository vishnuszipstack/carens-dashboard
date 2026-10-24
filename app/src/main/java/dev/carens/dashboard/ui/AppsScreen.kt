package dev.carens.dashboard.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.carens.dashboard.apps.AppsRepository
import dev.carens.dashboard.apps.LaunchableApp

/** Big-icon app drawer. Tap to open, long-press to pin/unpin on the Home dock. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(repo: AppsRepository, dock: List<String>, onToggleDock: (String) -> Unit) {
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = repo.installed() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Apps", onBack = null)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )
        Text(
            "Long-press an app to pin it to the Home dock.",
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading apps…") }
        } else {
            val shown = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.packageName }) { app ->
                    val pinned = app.packageName in dock
                    val bmp = remember(app.packageName) {
                        app.icon?.let { runCatching { it.toBitmap(160, 160).asImageBitmap() }.getOrNull() }
                    }
                    Column(
                        Modifier.clip(RoundedCornerShape(18.dp))
                            .combinedClickable(
                                onClick = { repo.launch(app.packageName) },
                                onLongClick = { onToggleDock(app.packageName) },
                            )
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            if (bmp != null) Image(bmp, app.label, Modifier.size(64.dp)) else Spacer(Modifier.size(64.dp))
                            if (pinned) {
                                Icon(
                                    Icons.Filled.PushPin,
                                    "Pinned to dock",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(app.label, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
