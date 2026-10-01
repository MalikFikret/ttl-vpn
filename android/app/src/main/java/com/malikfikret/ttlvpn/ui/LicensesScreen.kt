package com.malikfikret.ttlvpn.ui

import android.content.res.Configuration
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.malikfikret.ttlvpn.R
import com.malikfikret.ttlvpn.ui.theme.TTLVPNTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The open source notices: the app's own license and every bundled component, generated
// by tools/update_licenses.py into OSS_COMPONENTS and res/raw.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about_open_source_licenses),
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = stringResource(R.string.licenses_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.licenses_english_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LicenseSection.entries.forEach { section ->
                val components = OSS_COMPONENTS.filter { it.section == section }
                if (components.isEmpty()) return@forEach
                item(key = section) {
                    Text(
                        text = stringResource(
                            when (section) {
                                LicenseSection.App -> R.string.licenses_section_app
                                LicenseSection.Engine -> R.string.licenses_section_engine
                                LicenseSection.Android -> R.string.licenses_section_android
                            }
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }
                items(components, key = { "${section.name}/${it.name}" }) { ComponentTile(it) }
            }
        }
    }
}

@Composable
private fun ComponentTile(component: OssComponent) {
    var expanded by rememberSaveable(component.name) { mutableStateOf(false) }
    BrandTile(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            // Names, license ids and copyright lines are the components' own (Latin) text:
            // shown as-is in every language.
            Text(
                text = component.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = component.license,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = component.copyright,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            component.detail?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.licenses_hide else R.string.licenses_show))
            }
            if (expanded) LicenseText(component.text)
        }
    }
}

@Composable
private fun LicenseText(@RawRes res: Int) {
    val resources = LocalResources.current
    // Loaded only when expanded, off the main thread (the GPL text is ~35 KB).
    val text by produceState<String?>(initialValue = null, res, resources) {
        value = withContext(Dispatchers.IO) {
            resources.openRawResource(res).bufferedReader().use { it.readText() }
        }
    }
    // The texts are English: lay them out left to right even in the Arabic UI.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        SelectionContainer {
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp, bottom = 10.dp)
            )
        }
    }
}

// Previews: light and dark (@PreviewLightDark renders both), plus Arabic and Turkish.

@PreviewLightDark
@Composable
private fun LicensesPreview() {
    TTLVPNTheme { LicensesScreen(onBack = {}) }
}

@Preview(name = "Arabic · light", locale = "ar")
@Preview(name = "Arabic · dark", locale = "ar", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LicensesArabicPreview() {
    TTLVPNTheme { LicensesScreen(onBack = {}) }
}

@Preview(name = "Turkish", locale = "tr")
@Composable
private fun LicensesTurkishPreview() {
    TTLVPNTheme { LicensesScreen(onBack = {}) }
}
