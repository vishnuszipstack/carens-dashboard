package dev.carens.dashboard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlinx.coroutines.delay

object DashColors {
    val Warning = Color(0xFFFFB547)
    val Danger = Color(0xFFFF5A5F)
    val Accent = Color(0xFF34D399)      // speed / primary
    val Blue = Color(0xFF5B8CFF)        // map / navigation
    val Violet = Color(0xFFA78BFA)      // music
    val NavGreen = Color(0xFF0E7A4E)    // next-turn banner
}

private val DarkScheme = darkColorScheme(
    primary = DashColors.Accent,
    onPrimary = Color(0xFF00251A),
    secondary = DashColors.Blue,
    secondaryContainer = Color(0xFF1C2430),
    onSecondaryContainer = Color(0xFFEAF0F7),
    background = Color(0xFF06080B),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF10151C),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF19202A),
    onSurfaceVariant = Color(0xFF94A0B2),
    outline = Color(0xFF2B3442),
    outlineVariant = Color(0xFF1D2531),
    error = DashColors.Danger,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    secondary = Color(0xFF2F5BEA),
    secondaryContainer = Color(0xFFE3E8EF),
    onSecondaryContainer = Color(0xFF0B1220),
    background = Color(0xFFEDF0F4),
    onBackground = Color(0xFF0B1220),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFF1F4F8),
    onSurfaceVariant = Color(0xFF5B6678),
    outline = Color(0xFFCCD3DD),
    outlineVariant = Color(0xFFE2E7EE),
    error = Color(0xFFDC2626),
)

private val base = Typography()

/** Slightly larger, calmer type for reading at arm's length. */
private val DashTypography = base.copy(
    labelSmall = base.labelSmall.copy(fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelMedium = base.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp),
    titleMedium = base.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun DashTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = DashTypography,
        content = content,
    )
}

/** Soft vertical wash behind the widgets. */
fun dashBackground(dark: Boolean): Brush = if (dark) {
    Brush.verticalGradient(listOf(Color(0xFF0B1017), Color(0xFF05070A)))
} else {
    Brush.verticalGradient(listOf(Color(0xFFF3F5F8), Color(0xFFE6EAF0)))
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
