package com.malikfikret.ttlvpn.ui

import android.content.Context
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

// The card surface shared by both screens: white with an outline in light mode, a tonal
// teal surface in dark mode, or the primary container when highlighted.
@Composable
internal fun BrandTile(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val container = when {
        highlighted -> colors.primaryContainer
        dark -> colors.surfaceContainer
        else -> colors.surfaceContainerLowest
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = if (highlighted) colors.onPrimaryContainer else colors.onSurface,
        border = if (highlighted) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier,
        content = content
    )
}

@Composable
internal fun IconBadge(@DrawableRes iconRes: Int, tint: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null, // Decorative; a text label always accompanies it
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

// Honors Developer options / Accessibility "Remove animations" (animator scale 0).
// Re-read on every resume, so changing the setting applies without restarting the app.
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(isAnimationDisabled(context)) }
    LifecycleResumeEffect(context) {
        reduced = isAnimationDisabled(context)
        onPauseOrDispose { }
    }
    return reduced
}

private fun isAnimationDisabled(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
