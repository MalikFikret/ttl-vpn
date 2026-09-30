package com.malikfikret.ttlvpn.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.malikfikret.ttlvpn.R
import com.malikfikret.ttlvpn.TtlSettings
import com.malikfikret.ttlvpn.VpnState
import com.malikfikret.ttlvpn.ui.theme.TTLVPNTheme
import java.io.IOException
import kotlinx.coroutines.launch

// Stateful entry point: owns saving, then delegates to the stateless SettingsContent.
@Composable
fun SettingsScreen(
    vpnState: VpnState,
    savedTtl: Int?,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveFailed by remember { mutableStateOf(false) }

    SettingsContent(
        savedTtl = savedTtl,
        runningTtl = (vpnState as? VpnState.Connected)?.ttl,
        saveFailed = saveFailed,
        onSave = { ttl ->
            scope.launch {
                saveFailed = try {
                    TtlSettings.setTtl(context, ttl)
                    false
                } catch (e: IOException) {
                    Log.e("SettingsScreen", "Failed to save TTL", e)
                    true
                }
            }
        },
        onBack = onBack,
        onReconnect = onReconnect,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    savedTtl: Int?,        // null until DataStore has loaded
    runningTtl: Int?,      // TTL of the running session, null when not connected
    saveFailed: Boolean,
    onSave: (Int) -> Unit,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    initialInput: String? = null  // Pre-typed field text, for previews
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_section_ttl),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp)
            )
            TtlEditorTile(savedTtl, saveFailed, initialInput, onSave)

            // Only when connected with a TTL that differs from the saved one; changing
            // the value back hides it again.
            if (runningTtl != null && savedTtl != null && savedTtl != runningTtl) {
                PendingChangeTile(onReconnect = onReconnect)
            }

            ExplanationTile()
        }
    }
}

@Composable
private fun TtlEditorTile(
    savedTtl: Int?,
    saveFailed: Boolean,
    initialInput: String?,
    onSave: (Int) -> Unit
) {
    val focusManager = LocalFocusManager.current
    // null = not edited yet, so the field follows the saved value (which loads
    // asynchronously). Saveable, so a half-typed value survives rotation.
    var edited by rememberSaveable { mutableStateOf(initialInput) }
    val text = edited ?: savedTtl?.toString().orEmpty()
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in TtlSettings.TTL_RANGE
    val canSave = valid && parsed != savedTtl
    val showError = edited != null && !valid

    fun save() {
        if (canSave) {
            onSave(parsed!!)
            focusManager.clearFocus()
        }
    }

    BrandTile(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = text,
                // Digits only, at most 3; the range check happens on the parsed value.
                onValueChange = { input -> edited = input.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.settings_ttl_field_label)) },
                singleLine = true,
                isError = showError,
                supportingText = {
                    Text(
                        when {
                            showError -> stringResource(R.string.settings_ttl_error)
                            saveFailed -> stringResource(R.string.settings_save_failed)
                            edited != null && parsed == savedTtl ->
                                stringResource(R.string.settings_ttl_saved)
                            else -> stringResource(
                                R.string.settings_ttl_default_hint,
                                TtlSettings.DEFAULT_TTL
                            )
                        },
                        color = if (saveFailed && !showError) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                },
                textStyle = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = {
                        edited = TtlSettings.DEFAULT_TTL.toString()
                        if (savedTtl != TtlSettings.DEFAULT_TTL) onSave(TtlSettings.DEFAULT_TTL)
                        focusManager.clearFocus()
                    }
                ) {
                    Text(stringResource(R.string.settings_reset, TtlSettings.DEFAULT_TTL))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::save, enabled = canSave) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
    }
}

@Composable
private fun PendingChangeTile(onReconnect: () -> Unit) {
    BrandTile(modifier = Modifier.fillMaxWidth(), highlighted = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
        ) {
            IconBadge(iconRes = R.drawable.ic_ttl, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.settings_applies_next_connect),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onReconnect) {
                Text(stringResource(R.string.settings_reconnect_now))
            }
        }
    }
}

@Composable
private fun ExplanationTile() {
    BrandTile(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp)) {
            IconBadge(iconRes = R.drawable.ic_shield, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.settings_ttl_explanation_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_ttl_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Previews: light and dark (@PreviewLightDark renders both).

@PreviewLightDark
@Composable
private fun SettingsPreview() {
    TTLVPNTheme {
        SettingsContent(
            savedTtl = 63,
            runningTtl = null,
            saveFailed = false,
            onSave = {},
            onBack = {},
            onReconnect = {}
        )
    }
}

@PreviewLightDark
@Composable
private fun SettingsPendingChangePreview() {
    TTLVPNTheme {
        SettingsContent(
            savedTtl = 70,
            runningTtl = 63,
            saveFailed = false,
            onSave = {},
            onBack = {},
            onReconnect = {}
        )
    }
}

@PreviewLightDark
@Composable
private fun SettingsSaveFailedPreview() {
    TTLVPNTheme {
        SettingsContent(
            savedTtl = 63,
            runningTtl = null,
            saveFailed = true,
            onSave = {},
            onBack = {},
            onReconnect = {}
        )
    }
}

@PreviewLightDark
@Composable
private fun SettingsInvalidInputPreview() {
    TTLVPNTheme {
        SettingsContent(
            savedTtl = 63,
            runningTtl = null,
            saveFailed = false,
            onSave = {},
            onBack = {},
            onReconnect = {},
            initialInput = "300"
        )
    }
}
