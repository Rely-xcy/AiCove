package com.aicove.feature.agent.presentation.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicove.core.plugin.PluginSlotEntry
import com.aicove.feature.agent.domain.container.ConnectionState

internal fun chatPluginEntries(entries: List<PluginSlotEntry>, slot: String): List<PluginSlotEntry> =
    entries.filter { it.slot == slot && (it.kind == "page" || it.kind == "action") }

@Composable
internal fun ChatEmptyState(
    messagesReady: Boolean,
    isRemote: Boolean,
    connectionState: ConnectionState?,
    showMessagesLoading: Boolean,
    onRetryConnection: (() -> Unit)?,
    bottomReserve: Dp,
    pluginEntries: List<PluginSlotEntry>,
    onOpenPluginEntry: (PluginSlotEntry) -> Unit
) {
    if (messagesReady) {
        WelcomeState(
            bottomReserve = bottomReserve,
            modifier = Modifier.fillMaxSize(),
            pluginEntries = chatPluginEntries(pluginEntries, "chat.welcome"),
            onOpenPluginEntry = onOpenPluginEntry
        )
    } else if (isRemote && connectionState != null && connectionState != ConnectionState.CONNECTED) {
        // 远程模式连接未就绪时显示连接状态占位，避免空白或旧工作区记录闪烁
        RemoteConnectingPlaceholder(state = connectionState, onRetry = onRetryConnection)
    } else if (showMessagesLoading) {
        // 本地模式读库偏慢时的占位：以前这里什么都不画，切会话会先闪一下空白。
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
