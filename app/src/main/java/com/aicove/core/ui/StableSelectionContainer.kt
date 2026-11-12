package com.aicove.core.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/**
 * 按 [contentKey] 重建的选择容器。
 *
 * `SelectionContainer` 会把选中状态（TextRange）留在内部，而它依赖的文本随时可能变：
 * LazyColumn 复用换了一条消息、工具输出正在流式增长、轮转折叠切了 contentSlice。
 * 一旦文本变短，长按选词就会拿旧 offset 去构造 `TextSelection.Request`，Android 抛
 * `IllegalArgumentException`（崩溃栈顶是 `TextClassifier$Utils.checkArgument`）。
 *
 * 把文本本身当 key：内容一变就重建选择状态，宁可清掉选中也不拿错 offset。
 */
@Composable
fun StableSelectionContainer(
    contentKey: Any,
    content: @Composable () -> Unit
) {
    key(contentKey) {
        SelectionContainer {
            content()
        }
    }
}
