package com.aicove.feature.plugin.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import com.aicove.feature.plugin.domain.PluginPage
import com.aicove.feature.plugin.domain.PluginUiEntry

@Composable
fun PluginPageHost(
    entry: PluginUiEntry,
    languageTag: String,
    pluginsViewModel: PluginsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    applySystemBarInsets: Boolean = true,
    entryContext: () -> PluginEntryContext = { PluginEntryContext() }
) {
    entry.page?.let { page ->
        PluginPageHost(page, languageTag, pluginsViewModel, onClose, modifier, applySystemBarInsets, entryContext)
    }
}

@Composable
fun PluginPageHost(
    page: PluginPage,
    languageTag: String,
    pluginsViewModel: PluginsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    applySystemBarInsets: Boolean = true,
    entryContext: () -> PluginEntryContext = { PluginEntryContext() }
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        PluginWebPanelScreen(
            page = page,
            onClosePage = onClose,
            bridgeFactory = { post, close ->
                pluginsViewModel.createWebBridge(
                    page = page,
                    pluginVersion = page.pluginVersion,
                    languageTag = languageTag,
                    theme = { if (isDark) "dark" else "light" },
                    onClosePage = close,
                    post = post,
                    pageContext = entryContext
                )
            },
            modifier = Modifier.fillMaxSize(),
            applySystemBarInsets = applySystemBarInsets,
            entryContext = entryContext
        )
    }
}
