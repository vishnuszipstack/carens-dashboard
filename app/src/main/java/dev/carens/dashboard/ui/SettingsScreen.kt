package dev.carens.dashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.carens.dashboard.settings.DashSettings
import dev.carens.dashboard.settings.SpeedUnit
import dev.carens.dashboard.settings.ThemeMode
import dev.carens.dashboard.settings.VehicleProfile
import dev.carens.dashboard.update.ReleaseInfo
import dev.carens.dashboard.update.UpdateState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import kotlin.math.roundToInt

private const val LOCK_ABOVE_KMH = 10.0

@Composable
fun SettingsScreen(
    settings: DashSettings,
    speedKmh: Double?,
    onChange: ((DashSettings) -> DashSettings) -> Unit,
    onDeleteAllTrips: () -> Unit,
    update: UpdateUi,
    onBack: () -> Unit,
) {
    // Safety: no fiddling with settings while the car is moving.
    val locked = (speedKmh ?: 0.0) > LOCK_ABOVE_KMH
    val enabled = !locked
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader("Settings", onBack)
        if (locked) {
            Text(
                "Settings are locked while the car is moving.",
                color = DashColors.Warning,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { SectionTitle("App updates") }
            item { UpdateSection(update, enabled) }

            item { SectionTitle("Display") }
            item {
                ChoiceRow("Speed unit", SpeedUnit.entries, settings.unit, { it.label }, enabled) { u ->
                    onChange { it.copy(unit = u) }
                }
            }
            item {
                ChoiceRow("Theme", ThemeMode.entries, settings.themeMode, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, enabled) { t ->
                    onChange { it.copy(themeMode = t) }
                }
            }
            item {
                Text(
                    "Auto switches to dark between about 6:15 pm and 6:15 am, or whenever the phone is in dark mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SwitchRow("Keep screen on", settings.keepScreenOn, enabled) { v -> onChange { it.copy(keepScreenOn = v) } }
            }

            item { SectionTitle("Speed warning") }
            item {
                SwitchRow("Warn above a speed", settings.speedWarningEnabled, enabled) { v ->
                    onChange { it.copy(speedWarningEnabled = v) }
                }
            }
            item {
                val u = settings.unit
                // Drag locally, save once on release, so the slider doesn't stutter on storage round-trips.
                var draftKmh by remember(settings.speedWarningKmh) { mutableFloatStateOf(settings.speedWarningKmh.toFloat()) }
                val shown = u.fromKmh(draftKmh.toDouble()).roundToInt()
                Column {
                    Text("Threshold: $shown ${u.label}", fontSize = 16.sp)
                    Slider(
                        value = draftKmh,
                        onValueChange = { draftKmh = it },
                        onValueChangeFinished = {
                            val kmh = (draftKmh / 5f).roundToInt() * 5
                            onChange { it.copy(speedWarningKmh = kmh) }
                        },
                        valueRange = 30f..150f,
                        steps = 23,
                        enabled = enabled && settings.speedWarningEnabled,
                    )
                    Text(
                        "The speed number and arc turn red above this. No sounds or flashing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionTitle("Trips") }
            item {
                SwitchRow(
                    "Start and end trips automatically",
                    settings.autoTrip,
                    enabled,
                    "Starts after ~5 s above 10 km/h; ends after 5 min without moving.",
                ) { v -> onChange { it.copy(autoTrip = v) } }
            }

            item { SectionTitle("Engine data") }
            item {
                SwitchRow(
                    "Show engine data slots",
                    settings.showEcuSlots,
                    enabled,
                    "RPM, coolant and fuel can't be read from the car without extra hardware. " +
                        "These slots will only ever show real readings; for now they say Not available.",
                ) { v -> onChange { it.copy(showEcuSlots = v) } }
            }

            item { SectionTitle("Vehicle") }
            item { VehicleEditor(settings.vehicle, enabled) { p -> onChange { it.copy(vehicle = p) } } }

            item { SectionTitle("Privacy") }
            item {
                Text(
                    "Trips are stored only on this phone and never uploaded. GPS is used only while the " +
                        "dashboard is open or a trip is recording, and a notification is always shown.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = enabled) {
                    Text("Delete all trip history", color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Speed comes from GPS. It is not the car's certified speedometer reading and can lag " +
                        "or drop out in tunnels and under flyovers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all trips?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteAllTrips() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Spacer(Modifier.height(14.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    help: String? = null,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            if (help != null) {
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 16.sp)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun VehicleEditor(profile: VehicleProfile, enabled: Boolean, onChange: (VehicleProfile) -> Unit) {
    var nickname by rememberSaveable { mutableStateOf(profile.nickname) }
    var year by rememberSaveable { mutableStateOf(profile.modelYear) }
    var engine by rememberSaveable { mutableStateOf(profile.engineVariant) }
    val current = profile.copy(nickname = nickname, modelYear = year, engineVariant = engine)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kia Carens", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(24); onChange(current.copy(nickname = nickname)) },
            label = { Text("Nickname (shown on the dashboard)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        )
        OutlinedTextField(
            value = year,
            onValueChange = { v -> year = v.filter(Char::isDigit).take(4); onChange(current.copy(modelYear = year)) },
            label = { Text("Model year") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        )
        OutlinedTextField(
            value = engine,
            onValueChange = { engine = it.take(40); onChange(current.copy(engineVariant = engine)) },
            label = { Text("Engine variant (e.g. 1.5 Turbo GDi)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        )
        ChoiceRow("Fuel", listOf("Petrol", "Diesel", "Electric"), profile.fuelType, { it }, enabled) { f ->
            onChange(current.copy(fuelType = f))
        }
        ChoiceRow(
            "Transmission",
            listOf("Manual", "iMT", "DCT", "Automatic"),
            profile.transmission,
            { it },
            enabled,
        ) { t -> onChange(current.copy(transmission = t)) }
        Text(
            "These personalise the app only. They don't give access to the car's ECU.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

data class UpdateUi(
    val state: UpdateState,
    val versionName: String,
    val onCheck: () -> Unit,
    val onInstall: (ReleaseInfo) -> Unit,
    val onAllowInstalls: () -> Unit,
)

@Composable
private fun UpdateSection(ui: UpdateUi, enabled: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Installed: version ${ui.versionName}", fontSize = 16.sp)
        when (val st = ui.state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Text("Checking GitHub…", color = muted)
            is UpdateState.UpToDate -> Text("You're on the latest build.", color = MaterialTheme.colorScheme.primary)
            is UpdateState.Available -> {
                Text("Update available: ${st.release.name}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${"%.1f".format(st.release.sizeBytes / 1_048_576.0)} MB", color = muted)
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${(st.progress * 100).roundToInt()}%", color = muted)
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp))
            }
            is UpdateState.NeedsInstallPermission -> Text(
                "Android needs you to allow this app to install updates. Turn on \"Allow from this source\", come back, and tap Install again.",
                color = DashColors.Warning,
            )
            UpdateState.Installing -> Text("Installing… confirm on the system prompt if asked.", color = muted)
            is UpdateState.Failed -> Text(st.message, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val st = ui.state) {
                is UpdateState.Available -> Button(onClick = { ui.onInstall(st.release) }, enabled = enabled) {
                    Text("Download and install")
                }
                is UpdateState.NeedsInstallPermission -> {
                    Button(onClick = ui.onAllowInstalls, enabled = enabled) { Text("Allow installs") }
                    OutlinedButton(onClick = { ui.onInstall(st.release) }, enabled = enabled) { Text("Install") }
                }
                else -> {
                    val busy = st is UpdateState.Checking || st is UpdateState.Downloading || st is UpdateState.Installing
                    Button(onClick = ui.onCheck, enabled = enabled && !busy) { Text("Check for updates") }
                }
            }
        }
        Text(
            "Updates come from the latest release on GitHub. Your trips and settings are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}
