package com.aicove

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicove.core.plugin.PluginSlotEntry
import com.aicove.feature.plugin.domain.PluginUiEntry
import com.aicove.feature.plugin.presentation.PluginsViewModel
import com.aicove.feature.plugin.presentation.currentLanguageTag

@Composable
internal fun rememberPluginHostEntries(viewModel: PluginsViewModel): List<PluginUiEntry> {
    val installed by viewModel.plugins.collectAsStateWithLifecycle()
    val languageTag = currentLanguageTag()
    LaunchedEffect(viewModel, languageTag) { viewModel.setLanguageTag(languageTag) }
    return remember(installed) {
        installed.filter { it.isActive }.flatMap { it.content.uiEntries }
    }
}

internal fun PluginUiEntry.toHostEntry(languageTag: String) = PluginSlotEntry(
    pluginId = pluginId,
    localId = localId,
    title = title.resolve(languageTag),
    icon = icon,
    slot = slot,
    kind = kind.name.lowercase(java.util.Locale.ROOT),
    confirm = confirm
)
