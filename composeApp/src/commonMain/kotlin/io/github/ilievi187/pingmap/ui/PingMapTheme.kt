package io.github.ilievi187.pingmap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
