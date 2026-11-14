package com.aicove.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/** 工具栏自身的同步异常边界；智能选词的内部协程不经过这里，由启动策略单独关闭。 */
@Composable
fun ProvideSafeTextToolbar(content: @Composable () -> Unit) {
    val delegate = LocalTextToolbar.current
    val safe = remember(delegate) {
        object : TextToolbar {
            override val status: TextToolbarStatus get() = delegate.status

            override fun hide() = delegate.hide()

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?
            ) {
                runCatching {
                    delegate.showMenu(
                        rect = rect,
                        onCopyRequested = onCopyRequested,
                        onPasteRequested = onPasteRequested,
                        onCutRequested = onCutRequested,
                        onSelectAllRequested = onSelectAllRequested
                    )
                }
            }
        }
    }
    CompositionLocalProvider(LocalTextToolbar provides safe) { content() }
}
