package io.github.ilievi187.pingmap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object PingPalette {
    val Bg = Color(0xFF0A0F18)
    val Surface = Color(0xFF111827)
    val Card = Color(0xFF151F31)
    val CardSelected = Color(0xFF1B2A42)
    val Border = Color(0xFF24344D)
    val Text = Color(0xFFEAF2FC)
    val Muted = Color(0xFF8EA3BD)
    val Accent = Color(0xFF38BDF8)
    val AccentDark = Color(0xFF0284C7)
    val Excellent = Color(0xFF10B981)
    val Good = Color(0xFF38BDF8)
    val Fair = Color(0xFFF59E0B)
    val Poor = Color(0xFFEF4444)
}

/**
 * Cyber command-center palette and geometry tokens for the desktop PingMap dashboard.
 * Kept separate from [PingPalette] so Android's [PingMapApp] styling remains untouched.
 */
object DashboardPalette {
    val BgTop = Color(0xFF040810)
    val BgMid = Color(0xFF060E19)
    val BgBottom = Color(0xFF03070D)

    val RailTop = Color(0xFF0C1726)
    val RailBottom = Color(0xFF070F1A)

    val PanelTop = Color(0xFF0C1726)
    val PanelBottom = Color(0xFF070F1A)
    val PanelDeep = Color(0xFF060C15)
    val PanelRecessed = Color(0xFF050B13)

    val CardBase = Color(0xFF0A1522)
    val CardHover = Color(0xFF0F1F31)
    val CardSelected = Color(0xFF10263D)

    val BorderSubtle = Color(0xFF16283D)
    val BorderHighlight = Color(0xFF284866)
    val InnerTopHighlight = Color(0x26A8ECFF)
    val Divider = Color(0xFF132436)

    val Cyan = Color(0xFF32D2FF)
    val CyanBright = Color(0xFF7BE8FF)
    val MatrixGreen = Color(0xFF24F09A)
    val MatrixGreenDim = Color(0xFF10B574)
    val Amber = Color(0xFFFFBC52)
    val Coral = Color(0xFFFF5E6C)

    val TextPrimary = Color(0xFFEFF7FF)
    val TextSecondary = Color(0xFF8FA8C2)
    val TextMuted = Color(0xFF5B748E)
    val TextDim = Color(0xFF425970)
}

object DashboardDimens {
    val RailRadius = 18.dp
    val PanelRadius = 16.dp
    val InnerRadius = 14.dp
    val CardRadius = 12.dp
    val PillRadius = 10.dp
}

private val PingColorScheme = darkColorScheme(
    primary = PingPalette.Accent,
    onPrimary = Color(0xFF05131F),
    secondary = PingPalette.Excellent,
    onSecondary = Color(0xFF031912),
    background = PingPalette.Bg,
    onBackground = PingPalette.Text,
    surface = PingPalette.Surface,
    onSurface = PingPalette.Text,
    surfaceVariant = PingPalette.Card,
    onSurfaceVariant = PingPalette.Muted,
    outline = PingPalette.Border,
    error = PingPalette.Poor,
)

@Composable
fun PingMapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PingColorScheme,
        content = content,
    )
}
