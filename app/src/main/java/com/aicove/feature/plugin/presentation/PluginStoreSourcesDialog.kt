package com.aicove.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import com.aicove.R
import com.aicove.core.theme.Spacing
import com.aicove.core.ui.AppTextField
import com.aicove.core.ui.dialogTextFieldColors
import com.aicove.feature.plugin.data.index.PluginIndexDefaults
import com.aicove.feature.plugin.data.index.PluginIndexSource
import com.aicove.feature.settings.presentation.component.SettingsDivider
import com.aicove.feature.settings.presentation.component.SettingsGroup
import com.aicove.feature.settings.presentation.component.SettingsRow
import kotlinx.coroutines.launch

@Composable
internal fun pluginSourceName(source: PluginIndexSource): String =
    if (source.id == "aicode.official") stringResource(R.string.plugin_sources_upstream) else source.name

@Composable
internal fun PluginStoreSourcesDialog(
    sources: List<PluginIndexSource>,
    selectedSourceId: String?,
    onSelect: suspend (String) -> Result<Unit>,
    onSaveCustomSources: suspend (String) -> Result<Unit>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val savedCustomText = sources
        .filterNot { source -> PluginIndexDefaults.builtIns.any { it.id == source.id } }
        .joinToString("\n") { it.indexUrl }
    var customText by rememberSaveable(savedCustomText, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(savedCustomText))
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val saveError = stringResource(R.string.plugin_sources_save_error)
    val selectionError = stringResource(R.string.plugin_sources_select_error)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.plugin_sources_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Text(
                    text = stringResource(R.string.plugin_sources_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.muse_plugin_sources_all),
                        subtitle = stringResource(R.string.muse_plugin_sources_all_hint),
                        enabled = !busy && selectedSourceId != null,
                        trailing = {
                            if (selectedSourceId == PluginIndexDefaults.ALL_SOURCES_ID) {
                                Text(stringResource(R.string.plugin_sources_selected), color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                val result = onSelect(PluginIndexDefaults.ALL_SOURCES_ID)
                                busy = false
                                if (result.isFailure) error = selectionError
                            }
                        }
                    )
                    SettingsDivider()
                    sources.forEachIndexed { index, source ->
                        SettingsRow(
                            title = pluginSourceName(source),
                            subtitle = source.indexUrl,
                            subtitleMaxLines = 2,
                            enabled = !busy && selectedSourceId != null,
                            trailing = {
                                if (source.id == selectedSourceId) {
                                    Text(
                                        text = stringResource(R.string.plugin_sources_selected),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    val result = onSelect(source.id)
                                    busy = false
                                    if (result.isFailure) error = selectionError
                                }
                            }
                        )
                        if (index != sources.lastIndex) SettingsDivider()
                    }
                }
                AppTextField(
                    value = customText,
                    onValueChange = {
                        customText = it
                        error = null
                        saved = false
                    },
                    label = stringResource(R.string.plugin_sources_custom),
                    supportingText = { Text(stringResource(R.string.plugin_sources_custom_hint)) },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy,
                    isError = error != null,
                    colors = dialogTextFieldColors()
                )
                if (saved) {
                    Text(
                        text = stringResource(R.string.plugin_sources_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && selectedSourceId != null,
                onClick = {
                    busy = true
                    error = null
                    saved = false
                    scope.launch {
                        val result = onSaveCustomSources(customText.text)
                        busy = false
                        saved = result.isSuccess
                        if (result.isFailure) error = saveError
                    }
                }
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
