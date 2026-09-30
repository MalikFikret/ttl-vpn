package com.malikfikret.ttlvpn.ui

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.malikfikret.ttlvpn.R
import com.malikfikret.ttlvpn.VpnState
import com.malikfikret.ttlvpn.ui.theme.TTLVPNTheme
import com.malikfikret.ttlvpn.ui.theme.TtlVpnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Stateful entry point: owns the live session stats and the motion setting, then
// delegates to the stateless HomeContent (which previews can render with fake data).
@Composable
fun HomeScreen(
    state: VpnState,
    configuredTtl: Int?,
    onToggle: () -> Unit,
    onTtlClick: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable RowScope.() -> Unit = {}
) {
    val stats = (state as? VpnState.Connected)?.let { rememberSessionStats(it) }
    HomeContent(
        state = state,
        stats = stats,
        configuredTtl = configuredTtl,
        reducedMotion = rememberReducedMotion(),
        onToggle = onToggle,
        onTtlClick = onTtlClick,
        modifier = modifier,
        topBarActions = topBarActions
    )
}

// downloaded/uploaded: null until the first read, -1 if the device doesn't report
// per-UID counters.
@Immutable
data class SessionStats(val downloaded: Long?, val uploaded: Long?, val elapsedMillis: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: VpnState,
    stats: SessionStats?,
    configuredTtl: Int?,
    reducedMotion: Boolean,
    onToggle: () -> Unit,
    onTtlClick: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable RowScope.() -> Unit = {}
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { BrandTitle() },
                actions = topBarActions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val look = statusLook(state)
            // At least screen-tall, so SpaceBetween centers the hero above the stats on
            // tall phones; still scrolls on short ones or with large font scaling.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Spacer(Modifier.height(0.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    PowerToggle(state = state, look = look, reducedMotion = reducedMotion, onClick = onToggle)
                    Spacer(Modifier.height(20.dp))
                    StatusPill(look)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = look.hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state is VpnState.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                StatsGrid(state = state, stats = stats, configuredTtl = configuredTtl, onTtlClick = onTtlClick)
            }
        }
    }
}

@Composable
private fun BrandTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_stamp),
                contentDescription = null, // Decorative; the app name follows
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private data class StatusLook(
    val title: String,
    val hint: String,
    val actionLabel: String,
    val accent: Color,       // Pill text/dot, ring
    val halo: Color,
    val buttonTop: Color,
    val buttonBottom: Color,
    val buttonBorder: Color,
    val icon: Color,
)

@Composable
private fun statusLook(state: VpnState): StatusLook {
    val colors = MaterialTheme.colorScheme
    val status = TtlVpnTheme.statusColors
    val dark = colors.background.luminance() < 0.5f
    // Neutral raised button: lighter at the top in both themes.
    val neutralTop = if (dark) colors.surfaceContainerHighest else colors.surfaceContainerLowest
    val neutralBottom = if (dark) colors.surfaceContainerLow else colors.surfaceContainerHigh
    val connect = stringResource(R.string.action_connect)
    val disconnect = stringResource(R.string.action_disconnect)

    return when (state) {
        VpnState.Disconnected -> StatusLook(
            title = stringResource(R.string.status_disconnected),
            hint = stringResource(R.string.hint_tap_to_connect),
            actionLabel = connect,
            accent = colors.onSurfaceVariant,
            halo = colors.primary.copy(alpha = 0.10f),
            buttonTop = neutralTop,
            buttonBottom = neutralBottom,
            buttonBorder = colors.outlineVariant,
            icon = colors.primary,
        )
        VpnState.Connecting -> StatusLook(
            title = stringResource(R.string.status_connecting),
            hint = stringResource(R.string.hint_tap_to_cancel),
            actionLabel = disconnect,
            accent = status.connecting,
            halo = status.connecting.copy(alpha = 0.22f),
            buttonTop = neutralTop,
            buttonBottom = neutralBottom,
            buttonBorder = status.connecting.copy(alpha = 0.4f),
            icon = status.connecting,
        )
        is VpnState.Connected -> StatusLook(
            title = stringResource(R.string.status_connected),
            hint = stringResource(R.string.hint_tap_to_disconnect),
            actionLabel = disconnect,
            accent = status.connected,
            halo = status.connected.copy(alpha = 0.32f),
            buttonTop = status.connectedTop,
            buttonBottom = status.connected,
            buttonBorder = Color.White.copy(alpha = 0.2f),
            icon = status.onConnected,
        )
        is VpnState.Error -> StatusLook(
            title = stringResource(R.string.status_error),
            hint = state.message,
            actionLabel = connect,
            accent = colors.error,
            halo = colors.error.copy(alpha = 0.16f),
            buttonTop = neutralTop,
            buttonBottom = neutralBottom,
            buttonBorder = colors.error.copy(alpha = 0.4f),
            icon = colors.error,
        )
    }
}

@Composable
private fun PowerToggle(
    state: VpnState,
    look: StatusLook,
    reducedMotion: Boolean,
    onClick: () -> Unit
) {
    val halo by animateColorAsState(look.halo, label = "halo")
    val accent by animateColorAsState(look.accent, label = "accent")
    val top by animateColorAsState(look.buttonTop, label = "buttonTop")
    val bottom by animateColorAsState(look.buttonBottom, label = "buttonBottom")
    val border by animateColorAsState(look.buttonBorder, label = "buttonBorder")
    val icon by animateColorAsState(look.icon, label = "icon")

    // Slow breathing halo while connected.
    val pulse = if (state is VpnState.Connected && !reducedMotion) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.86f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 2400, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "pulseScale"
        ).value
    } else {
        1f
    }
    // Full 360° linear rotation. With animations off the arc stays still, which still
    // reads as "in progress" thanks to the amber color and the Connecting pill.
    val spin = if (state is VpnState.Connecting && !reducedMotion) {
        rememberInfiniteTransition(label = "spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
            label = "spinAngle"
        ).value
    } else {
        0f
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(264.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val haloRadius = size.minDimension / 2 * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(halo, halo.copy(alpha = 0f)),
                    center = center,
                    radius = haloRadius
                ),
                radius = haloRadius
            )

            val ringRadius = 106.dp.toPx()
            val ringStroke = 4.dp.toPx()
            drawCircle(color = accent.copy(alpha = 0.18f), radius = ringRadius, style = Stroke(ringStroke))
            val arcTopLeft = center.copy(x = center.x - ringRadius, y = center.y - ringRadius)
            val arcSize = Size(ringRadius * 2, ringRadius * 2)
            when (state) {
                VpnState.Connecting -> rotate(spin) {
                    // Comet tail: fades in along the arc, brightest at its leading end.
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to accent.copy(alpha = 0f),
                            0.75f to accent,
                            1f to accent.copy(alpha = 0f),
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(ringStroke, cap = StrokeCap.Round)
                    )
                }
                is VpnState.Connected -> drawCircle(
                    color = accent.copy(alpha = 0.55f),
                    radius = ringRadius,
                    style = Stroke(ringStroke)
                )
                else -> Unit
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(168.dp)
                .shadow(
                    elevation = if (state is VpnState.Connected) 18.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = accent,
                    spotColor = accent // Colored shadows need API 28+; plain below
                )
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .border(1.dp, border, CircleShape)
                .semantics {
                    // Announce the action and the current state, not just "button".
                    contentDescription = look.actionLabel
                    stateDescription = look.title
                }
                .clickable(role = Role.Button, onClick = onClick)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_power),
                contentDescription = null,
                tint = icon,
                modifier = Modifier.size(60.dp)
            )
        }
    }
}

@Composable
private fun StatusPill(look: StatusLook) {
    Surface(shape = CircleShape, color = look.accent.copy(alpha = 0.12f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(look.accent)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = look.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = look.accent
            )
        }
    }
}

@Composable
private fun StatsGrid(
    state: VpnState,
    stats: SessionStats?,
    configuredTtl: Int?,
    onTtlClick: () -> Unit
) {
    val context = LocalContext.current
    val none = stringResource(R.string.value_none)
    val unavailable = stringResource(R.string.value_unavailable)
    val connected = state as? VpnState.Connected

    fun bytes(value: Long?): String = when {
        connected == null || value == null -> none
        value < 0 -> unavailable
        else -> Formatter.formatShortFileSize(context, value)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                iconRes = R.drawable.ic_ttl,
                label = stringResource(R.string.label_ttl),
                // The TTL in use while connected; otherwise the one the next connect uses.
                value = (connected?.ttl ?: configuredTtl)?.toString() ?: none,
                onClick = onTtlClick,
                clickLabel = stringResource(R.string.action_change_ttl),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                iconRes = R.drawable.ic_timer,
                label = stringResource(R.string.label_duration),
                value = if (connected != null) formatDuration(stats?.elapsedMillis ?: 0) else none,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                iconRes = R.drawable.ic_download,
                label = stringResource(R.string.label_downloaded),
                value = bytes(stats?.downloaded),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                iconRes = R.drawable.ic_upload,
                label = stringResource(R.string.label_uploaded),
                value = bytes(stats?.uploaded),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// All tiles share one surface. The TTL tile stands out by function instead: it's
// tappable, carries an edit icon, and opens Settings.
@Composable
private fun StatTile(
    @DrawableRes iconRes: Int,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null
) {
    val colors = MaterialTheme.colorScheme
    val tileModifier = if (onClick != null) {
        val description = stringResource(R.string.cd_ttl_tile, value)
        modifier
            .clip(TileShape)
            // One focus stop that reads "Outgoing TTL 63, double-tap to change TTL":
            // the value as the label and the action as the click label, instead of
            // TalkBack's generic "double-tap to activate".
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = clickLabel) { onClick(); true }
            }
            .clickable(onClick = onClick)
    } else {
        modifier
    }

    BrandTile(modifier = tileModifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(iconRes = iconRes, tint = colors.primary)
                Spacer(Modifier.weight(1f))
                if (onClick != null) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = null, // Covered by the tile's click label
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // Tabular figures, so ticking numbers don't jitter sideways.
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

// Polls once a second, only while the screen is visible, so nothing runs in the
// background with the screen off. Restarts from the new baseline on every new session.
@Composable
private fun rememberSessionStats(session: VpnState.Connected): SessionStats {
    var stats by remember(session) {
        mutableStateOf(SessionStats(null, null, SystemClock.elapsedRealtime() - session.startedAt))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(session, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // Off the main thread: on Android 12+ each read is a binder call.
                stats = withContext(Dispatchers.IO) { readStats(session) }
                delay(1_000)
            }
        }
    }
    return stats
}

private fun readStats(session: VpnState.Connected): SessionStats {
    val uid = Process.myUid()
    return SessionStats(
        downloaded = delta(TrafficStats.getUidRxBytes(uid), session.rxBaseline),
        uploaded = delta(TrafficStats.getUidTxBytes(uid), session.txBaseline),
        elapsedMillis = SystemClock.elapsedRealtime() - session.startedAt
    )
}

private fun delta(now: Long, baseline: Long): Long {
    val unsupported = TrafficStats.UNSUPPORTED.toLong()
    return if (now == unsupported || baseline == unsupported) -1 else (now - baseline).coerceAtLeast(0)
}

// Previews: every state in light and dark (@PreviewLightDark renders both).

private val previewStats = SessionStats(
    downloaded = 48_300_000,
    uploaded = 3_150_000,
    elapsedMillis = 754_000
)

@PreviewLightDark
@Composable
private fun DisconnectedPreview() {
    TTLVPNTheme {
        HomeContent(VpnState.Disconnected, stats = null, configuredTtl = 63, reducedMotion = true, onToggle = {}, onTtlClick = {})
    }
}

@PreviewLightDark
@Composable
private fun ConnectingPreview() {
    TTLVPNTheme {
        HomeContent(VpnState.Connecting, stats = null, configuredTtl = 63, reducedMotion = true, onToggle = {}, onTtlClick = {})
    }
}

@PreviewLightDark
@Composable
private fun ConnectedPreview() {
    TTLVPNTheme {
        HomeContent(
            VpnState.Connected(ttl = 63, rxBaseline = 0, txBaseline = 0, startedAt = 0),
            stats = previewStats,
            configuredTtl = 63,
            reducedMotion = true,
            onToggle = {},
            onTtlClick = {}
        )
    }
}

@PreviewLightDark
@Composable
private fun ErrorPreview() {
    TTLVPNTheme {
        HomeContent(
            VpnState.Error("Engine failed to start: invalid ttl: 0"),
            stats = null,
            configuredTtl = 63,
            reducedMotion = true,
            onToggle = {},
            onTtlClick = {}
        )
    }
}

// A short phone, to check the layout scrolls instead of clipping.
@Preview(name = "Small phone", widthDp = 320, heightDp = 568)
@Composable
private fun SmallPhonePreview() {
    TTLVPNTheme {
        HomeContent(
            VpnState.Connected(ttl = 63, rxBaseline = 0, txBaseline = 0, startedAt = 0),
            stats = previewStats,
            configuredTtl = 63,
            reducedMotion = true,
            onToggle = {},
            onTtlClick = {}
        )
    }
}
