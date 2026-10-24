package dev.carens.dashboard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import java.util.Calendar
import kotlinx.coroutines.delay

object DashColors {
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF4D4F)
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF00210F),
    secondaryContainer = Color(0xFF1B222C),
    onSecondaryContainer = Color(0xFFE8EDF2),
    background = Color(0xFF05070A),
    onBackground = Color(0xFFE8EDF2),
    surface = Color(0xFF0E1218),
    onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Color(0xFF141A22),
    onSurfaceVariant = Color(0xFF8B96A5),
    outline = Color(0xFF2A313C),
    outlineVariant = Color(0xFF1E252F),
    error = DashColors.Danger,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00875A),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFDDE3EA),
    onSecondaryContainer = Color(0xFF0B0E12),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF0B0E12),
    surface = Color.White,
    onSurface = Color(0xFF0B0E12),
    surfaceVariant = Color(0xFFE9ECF0),
    onSurfaceVariant = Color(0xFF4A5462),
    outline = Color(0xFFCBD2DA),
    outlineVariant = Color(0xFFDDE2E8),
    error = Color(0xFFD92D20),
)

@Composable
fun DashTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}

/** Rough night window for India (sunset ~18:00–18:45). Re-evaluated every minute. */
@Composable
fun rememberIsNight(): State<Boolean> = produceState(initialValue = isNightNow()) {
    while (true) {
        value = isNightNow()
        delay(60_000)
    }
}

private fun isNightNow(): Boolean {
    val c = Calendar.getInstance()
    val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    return minutes < 6 * 60 + 15 || minutes >= 18 * 60 + 15
}
