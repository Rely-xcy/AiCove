package com.aicove.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicove.R
import com.aicove.core.theme.Spacing

@Composable
internal fun PluginPublishHost(
    viewModel: PluginPublishViewModel,
    onPickPackage: () -> Unit,
    onExport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val meta = state.meta
    if (state.busy || meta == null || state.submitted) {
        PluginPublishStatusDialog(
            busy = state.busy,
            statusText = state.status ?: stringResource(R.string.muse_plugin_publish_pick_hint),
            onDismiss = onDismiss,
            onPickPackage = if (state.busy) null else onPickPackage
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.muse_plugin_publish_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(meta.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.muse_plugin_publish_identity, meta.id, meta.version, meta.author))
                Text(meta.description, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.muse_plugin_publish_license, meta.license), style = MaterialTheme.typography.bodySmall)
                if (!viewModel.backendConfigured) {
                    Text(stringResource(R.string.muse_plugin_publish_unconfigured), color = MaterialTheme.colorScheme.error)
                }
                state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::submit) { Text(stringResource(R.string.muse_plugin_publish_submit)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onExport("${meta.id}_${meta.version}.zip") }) {
                    Text(stringResource(R.string.muse_plugin_publish_export))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }
        }
    )
}
