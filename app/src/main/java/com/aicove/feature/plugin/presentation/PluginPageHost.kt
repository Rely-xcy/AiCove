package com.aicove.feature.plugin.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import com.aicove.feature.plugin.domain.PluginSettingsPage
import com.aicove.feature.plugin.domain.PluginUiEntry

/** 槽位入口与配置页共用同一套 WebView 宿主，这里只做一次字段搬运。 */
private fun PluginUiEntry.asSettingsPage() = PluginSettingsPage(
    pluginId = pluginId,
    localId = localId,
    title = title,
    pageFile = pageFile,
    icon = icon,
    installDir = installDir,
    pluginVersion = pluginVersion
)

/** 插件页面宿主：设置页、侧栏与顶栏菜单共用同一套 WebView 与桥接逻辑。 */
@Composable
fun PluginPageHost(
    entry: PluginUiEntry,
    languageTag: String,
    pluginsViewModel: PluginsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    applySystemBarInsets: Boolean = true
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        PluginWebPanelScreen(
            page = entry.asSettingsPage(),
            onClosePage = onClose,
            bridgeFactory = { post, close ->
                pluginsViewModel.createWebBridge(
                    page = entry.asSettingsPage(),
                    pluginVersion = entry.pluginVersion,
                    languageTag = languageTag,
                    theme = { if (isDark) "dark" else "light" },
                    onClosePage = close,
                    post = post
                )
            },
            modifier = Modifier.fillMaxSize(),
            applySystemBarInsets = applySystemBarInsets
        )
    }
}
