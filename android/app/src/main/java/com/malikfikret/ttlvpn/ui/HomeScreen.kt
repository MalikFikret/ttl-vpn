package com.malikfikret.ttlvpn.ui

import android.net.TrafficStats
import android.os.Process
import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: VpnState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val look = statusLook(state)

            ToggleButton(state = state, look = look, onClick = onToggle)
            Spacer(Modifier.height(24.dp))
            Text(
                text = look.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = look.accent
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = look.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is VpnState.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            TtlCard(ttl = (state as? VpnState.Connected)?.ttl)
            Spacer(Modifier.height(12.dp))
            UsageCard(session = state as? VpnState.Connected)
        }
    }
}

private data class StatusLook(
    val title: String,
    val subtitle: String,
    val accent: Color,
    val container: Color,
    val content: Color,
    val actionLabel: String
)

@Composable
private fun statusLook(state: VpnState): StatusLook {
    val colors = MaterialTheme.colorScheme
    val status = TtlVpnTheme.statusColors
    return when (state) {
        VpnState.Disconnected -> StatusLook(
            title = stringResource(R.string.status_disconnected),
            subtitle = stringResource(R.string.hint_tap_to_connect),
            accent = colors.onSurfaceVariant,
            container = colors.surfaceContainerHigh,
            content = colors.onSurfaceVariant,
            actionLabel = stringResource(R.string.action_connect)
        )
        VpnState.Connecting -> StatusLook(
            title = stringResource(R.string.status_connecting),
            subtitle = stringResource(R.string.hint_tap_to_cancel),
            accent = status.connecting,
            container = status.connecting.copy(alpha = 0.16f),
            content = status.connecting,
            actionLabel = stringResource(R.string.action_disconnect)
        )
        is VpnState.Connected -> StatusLook(
            title = stringResource(R.string.status_connected),
            subtitle = stringResource(R.string.hint_tap_to_disconnect),
            accent = status.connected,
            container = status.connected,
            content = colors.surface,
            actionLabel = stringResource(R.string.action_disconnect)
        )
        is VpnState.Error -> StatusLook(
            title = stringResource(R.string.status_error),
            subtitle = state.message,
            accent = colors.error,
            container = colors.error.copy(alpha = 0.14f),
            content = colors.error,
            actionLabel = stringResource(R.string.action_connect)
        )
    }
}

@Composable
private fun ToggleButton(state: VpnState, look: StatusLook, onClick: () -> Unit) {
    val container by animateColorAsState(look.container, label = "toggleContainer")
    val content by animateColorAsState(look.content, label = "toggleContent")

    Box(contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            shadowElevation = if (state is VpnState.Connected) 6.dp else 0.dp,
            modifier = Modifier
                .size(184.dp)
                .semantics {
                    // Read out the action and the current state, not just "button".
                    stateDescription = look.title
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_power),
                    contentDescription = look.actionLabel,
                    modifier = Modifier.size(72.dp)
                )
            }
        }
        if (state is VpnState.Connecting) {
            CircularProgressIndicator(
                color = look.accent,
                strokeWidth = 4.dp,
                modifier = Modifier.size(200.dp)
            )
        }
    }
}

@Composable
private fun TtlCard(ttl: Int?) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.label_ttl),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = ttl?.toString() ?: stringResource(R.string.value_none),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun UsageCard(session: VpnState.Connected?) {
    val usage = session?.let { rememberSessionUsage(it) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.label_session_usage),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                UsageItem(
                    iconRes = R.drawable.ic_download,
                    label = stringResource(R.string.label_downloaded),
                    bytes = usage?.downloaded,
                    active = session != null,
                    modifier = Modifier.weight(1f)
                )
                UsageItem(
                    iconRes = R.drawable.ic_upload,
                    label = stringResource(R.string.label_uploaded),
                    bytes = usage?.uploaded,
                    active = session != null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun UsageItem(
    iconRes: Int,
    label: String,
    bytes: Long?,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null, // The text label next to it says the same
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    !active || bytes == null -> stringResource(R.string.value_none)
                    bytes < 0 -> stringResource(R.string.value_unavailable)
                    else -> Formatter.formatShortFileSize(context, bytes)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// -1 means the device doesn't report per-UID counters.
private data class SessionUsage(val downloaded: Long, val uploaded: Long)

// Polls once a second, only while the screen is visible, so nothing runs in the
// background with the screen off. Restarts from the new baseline on every new session.
@Composable
private fun rememberSessionUsage(session: VpnState.Connected): SessionUsage? {
    var usage by remember(session) { mutableStateOf<SessionUsage?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(session, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // Off the main thread: on Android 12+ each read is a binder call.
                usage = withContext(Dispatchers.IO) { readUsage(session) }
                delay(1_000)
            }
        }
    }
    return usage
}

private fun readUsage(session: VpnState.Connected): SessionUsage {
    val uid = Process.myUid()
    return SessionUsage(
        downloaded = delta(TrafficStats.getUidRxBytes(uid), session.rxBaseline),
        uploaded = delta(TrafficStats.getUidTxBytes(uid), session.txBaseline)
    )
}

private fun delta(now: Long, baseline: Long): Long =
    if (now == TrafficStats.UNSUPPORTED.toLong() || baseline == TrafficStats.UNSUPPORTED.toLong()) {
        -1
    } else {
        (now - baseline).coerceAtLeast(0)
    }

@Preview(showBackground = true)
@Composable
private fun HomeScreenConnectedPreview() {
    TTLVPNTheme {
        HomeScreen(state = VpnState.Connected(63, 0, 0), onToggle = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenErrorPreview() {
    TTLVPNTheme(darkTheme = true) {
        HomeScreen(state = VpnState.Error("Engine failed to start: invalid ttl: 0"), onToggle = {})
    }
}
