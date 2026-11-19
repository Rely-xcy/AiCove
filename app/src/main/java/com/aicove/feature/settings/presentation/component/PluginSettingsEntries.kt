package com.aicove.feature.settings.presentation.component

import androidx.compose.runtime.Composable
import com.aicove.core.plugin.PluginSlotEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.Package

internal fun settingsPluginEntries(entries: List<PluginSlotEntry>, slot: String): List<PluginSlotEntry> =
    entries.filter { it.slot == slot && it.kind == "page" }

@Composable
internal fun PluginSettingsRows(
    entries: List<PluginSlotEntry>,
    slot: String,
    onOpen: (PluginSlotEntry) -> Unit
) {
    settingsPluginEntries(entries, slot).forEach { entry ->
        SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.Package,
            title = entry.title,
            subtitle = entry.pluginId,
            onClick = { onOpen(entry) }
        )
    }
}
