package com.malikfikret.ttlvpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = TealPrimaryLight,
    onPrimary = OnTealPrimaryLight,
    primaryContainer = TealPrimaryContainerLight,
    onPrimaryContainer = OnTealPrimaryContainerLight,
    secondary = SlateSecondaryLight,
    secondaryContainer = SlateSecondaryContainerLight,
    onSecondaryContainer = OnSlateSecondaryContainerLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    outline = OutlineLight,
    error = ErrorLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = OnTealPrimaryDark,
    primaryContainer = TealPrimaryContainerDark,
    onPrimaryContainer = OnTealPrimaryContainerDark,
    secondary = SlateSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark,
    onSecondaryContainer = OnSlateSecondaryContainerDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    outline = OutlineDark,
    error = ErrorDark,
)

// Status colors Material 3 has no role for.
@Immutable
data class StatusColors(val connected: Color, val connecting: Color)

private val LightStatusColors = StatusColors(connected = ConnectedLight, connecting = ConnectingLight)
private val DarkStatusColors = StatusColors(connected = ConnectedDark, connecting = ConnectingDark)

private val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

object TtlVpnTheme {
    val statusColors: StatusColors
        @Composable get() = LocalStatusColors.current
}

// Fixed brand colors on purpose (no dynamic color), so every state looks the same on
// every device.
@Composable
fun TTLVPNTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content
        )
    }
}
