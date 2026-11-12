package com.aicove.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * 把系统的选择工具栏包一层异常兜底。
 *
 * Compose 在长按选词时会延迟显示工具栏，用的却是**当时**缓存的 selection offset。
 * 文本在那之后被删短（一边退格一边长按是最容易命中的路径），构造
 * `TextSelection.Request` 就会抛 IllegalArgumentException：
 *
 * ```
 * TextClassifier$Utils.checkArgument
 *   ← TextSelection$Request$Builder.<init>
 *   ← TextToolbar.showMenu
 * ```
 *
 * 调用点分散在 `SelectionContainer`（消息正文）与 `TextField`（各类输入框）两套
 * SelectionManager 里，逐个堵漏已经失败过三次。这里从唯一的出口兜住：
 * 正常时行为不变，越界时只是这次不弹菜单，进程不崩。
 */
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
