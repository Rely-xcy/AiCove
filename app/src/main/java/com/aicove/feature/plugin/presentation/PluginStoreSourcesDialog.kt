package com.aicove.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicove.R
import com.aicove.core.theme.Radius
import com.aicove.core.theme.Spacing
import com.aicove.core.theme.semanticColors
import com.aicove.core.ui.AdaptiveModalBottomSheet
import com.aicove.core.ui.AppTextField
import com.aicove.core.ui.dialogTextFieldColors
import com.aicove.feature.plugin.data.index.PluginIndexDefaults
import com.aicove.feature.plugin.data.index.PluginIndexSource
import com.aicove.feature.settings.presentation.component.SettingsDivider
import com.aicove.feature.settings.presentation.component.SettingsGroup
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Server
import compose.icons.feathericons.X
import kotlinx.coroutines.launch

@Composable
internal fun pluginSourceName(source: PluginIndexSource): String =
    if (source.id == "aicode.official") stringResource(R.string.plugin_sources_upstream) else source.name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginStoreSourcesSheet(
    sources: List<PluginIndexSource>,
    selectedSourceId: String?,
    loadedCountsBySourceId: Map<String, Int>,
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
    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val saveError = stringResource(R.string.plugin_sources_save_error)
    val selectionError = stringResource(R.string.plugin_sources_select_error)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allSelected = selectedSourceId == PluginIndexDefaults.ALL_SOURCES_ID
    val customCount = sources.count { source ->
        PluginIndexDefaults.builtIns.none { it.id == source.id }
    }
    val customStateDescription = stringResource(
        if (customExpanded) R.string.plugin_sources_custom_expanded
        else R.string.plugin_sources_custom_collapsed
    )

    fun switchSource(sourceId: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                if (onSelect(sourceId).isSuccess) onDismiss() else error = selectionError
            } finally {
                busy = false
            }
        }
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.semanticColors.pageBackground,
        sheetGesturesEnabled = !busy
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.plugin_sources_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.plugin_sources_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                }
                IconButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(R.string.common_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            SettingsGroup {
                PluginSourceOption(
                    title = stringResource(R.string.muse_plugin_sources_all),
                    subtitle = stringResource(R.string.muse_plugin_sources_all_hint),
                    trailing = stringResource(R.string.plugin_sources_index_count, sources.size),
                    selected = allSelected,
                    enabled = !busy && selectedSourceId != null
                ) {
                    switchSource(PluginIndexDefaults.ALL_SOURCES_ID)
                }
                SettingsDivider()
                sources.forEachIndexed { index, source ->
                    PluginSourceOption(
                        title = pluginSourceName(source),
                        subtitle = source.indexUrl,
                        trailing = loadedCountsBySourceId[source.id]?.let {
                            stringResource(R.string.plugin_sources_item_count, it)
                        },
                        selected = source.id == selectedSourceId,
                        enabled = !busy && selectedSourceId != null
                    ) {
                        switchSource(source.id)
                    }
                    if (index != sources.lastIndex) SettingsDivider()
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.xs, top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.plugin_sources_custom),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.plugin_sources_custom_count, customCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                onClick = { customExpanded = !customExpanded },
                enabled = !busy,
                color = MaterialTheme.semanticColors.cardSurface,
                shape = RoundedCornerShape(Radius.mdLarge),
                modifier = Modifier.semantics { stateDescription = customStateDescription }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = FeatherIcons.Edit2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = stringResource(R.string.plugin_sources_manage_custom),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = FeatherIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = if (customExpanded) 180f else 0f }
                    )
                }
            }

            if (customExpanded) {
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = !busy && selectedSourceId != null,
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                saved = false
                                val result = onSaveCustomSources(customText.text)
                                busy = false
                                saved = result.isSuccess
                                if (result.isFailure) error = saveError
                            }
                        }
                    ) { Text(stringResource(R.string.common_save)) }
                }
            }

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
    }
}

@Composable
private fun PluginSourceOption(
    title: String,
    subtitle: String,
    trailing: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.semanticColors.mutedSurface else Color.Transparent,
        shape = RoundedCornerShape(Radius.sm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick
                )
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.Server,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailing?.let {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Spacer(Modifier.width(Spacing.sm))
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
