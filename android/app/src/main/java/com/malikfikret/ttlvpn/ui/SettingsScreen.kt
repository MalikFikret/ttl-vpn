package com.malikfikret.ttlvpn.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.malikfikret.ttlvpn.AppSettings
import com.malikfikret.ttlvpn.R
import com.malikfikret.ttlvpn.ThemeMode
import com.malikfikret.ttlvpn.VpnState
import com.malikfikret.ttlvpn.ui.theme.TTLVPNTheme
import java.io.IOException
import kotlinx.coroutines.launch

// Outcome of the last TTL save, shown under the field until the next edit.
enum class SaveResult { Idle, Saved, Failed }

// Stateful entry point: owns saving, then delegates to the stateless SettingsContent.
@Composable
fun SettingsScreen(
    vpnState: VpnState,
    savedTtl: Int?,
    themeMode: ThemeMode,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveResult by rememberSaveable { mutableStateOf(SaveResult.Idle) }
    // Bumped on every edit, so a save that completes after the user has typed again
    // doesn't report "Saved" for a value that's no longer in the field.
    var editGeneration by rememberSaveable { mutableIntStateOf(0) }

    SettingsContent(
        savedTtl = savedTtl,
        runningTtl = (vpnState as? VpnState.Connected)?.ttl,
        saveResult = saveResult,
        themeMode = themeMode,
        onEdited = {
            editGeneration++
            saveResult = SaveResult.Idle
        },
        onSave = { ttl ->
            val generation = editGeneration
            scope.launch {
                val result = try {
                    AppSettings.setTtl(context, ttl)
                    SaveResult.Saved
                } catch (e: IOException) {
                    Log.e("SettingsScreen", "Failed to save TTL", e)
                    SaveResult.Failed
                }
                if (generation == editGeneration) saveResult = result
            }
        },
        onThemeChange = { mode ->
            scope.launch {
                try {
                    AppSettings.setThemeMode(context, mode)
                } catch (e: IOException) {
                    // The selection simply doesn't change; nothing else to recover.
                    Log.e("SettingsScreen", "Failed to save theme", e)
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
    saveResult: SaveResult,
    themeMode: ThemeMode,
    onEdited: () -> Unit,
    onSave: (Int) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
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
            SectionTitle(stringResource(R.string.settings_section_ttl))
            TtlEditorTile(savedTtl, saveResult, initialInput, onEdited, onSave)

            // Only when connected with a TTL that differs from the saved one; changing
            // the value back hides it again.
            if (runningTtl != null && savedTtl != null && savedTtl != runningTtl) {
                PendingChangeTile(onReconnect = onReconnect)
            }

            ExplanationTile()

            Spacer(Modifier.height(8.dp))
            SectionTitle(stringResource(R.string.settings_section_appearance))
            ThemeTile(selected = themeMode, onSelect = onThemeChange)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun TtlEditorTile(
    savedTtl: Int?,
    saveResult: SaveResult,
    initialInput: String?,
    onEdited: () -> Unit,
    onSave: (Int) -> Unit
) {
    val focusManager = LocalFocusManager.current
    // null = not edited yet, so the field follows the saved value (which loads
    // asynchronously). Saveable, so a half-typed value survives rotation.
    var edited by rememberSaveable { mutableStateOf(initialInput) }
    val text = edited ?: savedTtl?.toString().orEmpty()
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in AppSettings.TTL_RANGE
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
                onValueChange = { input ->
                    val digits = input.filter(Char::isDigit).take(3)
                    // Cursor moves and rejected characters also land here; only a real
                    // text change counts as an edit (and clears "Saved").
                    if (digits != text) {
                        edited = digits
                        onEdited()
                    }
                },
                label = { Text(stringResource(R.string.settings_ttl_field_label)) },
                singleLine = true,
                isError = showError,
                supportingText = {
                    val failed = !showError && saveResult == SaveResult.Failed
                    Text(
                        text = when {
                            showError -> stringResource(R.string.settings_ttl_error)
                            failed -> stringResource(R.string.settings_save_failed)
                            // Set only once the write has completed, never derived from
                            // the field happening to match the stored value.
                            saveResult == SaveResult.Saved -> stringResource(R.string.settings_ttl_saved)
                            else -> stringResource(
                                R.string.settings_ttl_default_hint,
                                AppSettings.DEFAULT_TTL
                            )
                        },
                        color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified
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
                        edited = AppSettings.DEFAULT_TTL.toString()
                        onEdited()
                        // Already the default: nothing to save, so no "Saved" either.
                        if (savedTtl != AppSettings.DEFAULT_TTL) onSave(AppSettings.DEFAULT_TTL)
                        focusManager.clearFocus()
                    }
                ) {
                    Text(stringResource(R.string.settings_reset, AppSettings.DEFAULT_TTL))
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

@Composable
private fun ThemeTile(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.System to stringResource(R.string.theme_system),
        ThemeMode.Light to stringResource(R.string.theme_light),
        ThemeMode.Dark to stringResource(R.string.theme_dark),
    )
    BrandTile(modifier = Modifier.fillMaxWidth()) {
        // selectableGroup + Role.RadioButton: TalkBack announces "selected, 1 of 3".
        Column(modifier = Modifier.selectableGroup().padding(vertical = 8.dp)) {
            options.forEach { (mode, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = mode == selected,
                            onClick = { onSelect(mode) },
                            role = Role.RadioButton
                        )
                        .padding(horizontal = 16.dp)
                ) {
                    // onClick = null: the whole row is the touch target and the
                    // semantic node, so the radio doesn't get a separate focus stop.
                    RadioButton(selected = mode == selected, onClick = null)
                    Spacer(Modifier.width(16.dp))
                    Text(text = label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// Previews: light and dark (@PreviewLightDark renders both).

@Composable
private fun PreviewSettings(
    savedTtl: Int = 63,
    runningTtl: Int? = null,
    saveResult: SaveResult = SaveResult.Idle,
    themeMode: ThemeMode = ThemeMode.System,
    initialInput: String? = null
) {
    TTLVPNTheme {
        SettingsContent(
            savedTtl = savedTtl,
            runningTtl = runningTtl,
            saveResult = saveResult,
            themeMode = themeMode,
            onEdited = {},
            onSave = {},
            onThemeChange = {},
            onBack = {},
            onReconnect = {},
            initialInput = initialInput
        )
    }
}

@PreviewLightDark
@Composable
private fun SettingsPreview() = PreviewSettings()

@PreviewLightDark
@Composable
private fun SettingsSavedPreview() =
    PreviewSettings(savedTtl = 70, saveResult = SaveResult.Saved, initialInput = "70")

@PreviewLightDark
@Composable
private fun SettingsPendingChangePreview() = PreviewSettings(savedTtl = 70, runningTtl = 63)

@PreviewLightDark
@Composable
private fun SettingsInvalidInputPreview() = PreviewSettings(initialInput = "300")

@PreviewLightDark
@Composable
private fun SettingsSaveFailedPreview() = PreviewSettings(saveResult = SaveResult.Failed)

@PreviewLightDark
@Composable
private fun SettingsThemeLightSelectedPreview() = PreviewSettings(themeMode = ThemeMode.Light)

@PreviewLightDark
@Composable
private fun SettingsThemeDarkSelectedPreview() = PreviewSettings(themeMode = ThemeMode.Dark)
