package com.aicove.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 安全的可编辑文本状态：外部改值时把光标移到末尾，并清掉 IME 组合区。
 *
 * 为什么必须用 TextFieldValue 而不是 String 重载：
 * String 重载在文本被外部改写时（插入 @ 文件引用、快捷命令补全、清空按钮）只重置 selection，
 * 而 IME 的组合区（composition）仍指向旧文本。之后长按选词，Compose 会拿越界 offset 去构造
 * TextSelection.Request，Android 直接抛 IllegalArgumentException —— 真机崩溃于聊天页。
 *
 * 用法：state.value 交给 TextField 的 TextFieldValue 重载；框内编辑写回 state.value，
 * 再把新文本通知外部持有者。
 *
 * @param text 外部持有的文本真值。
 */
@Composable
internal fun rememberSafeTextFieldValueState(text: String): MutableState<TextFieldValue> {
    val state = remember { mutableStateOf(TextFieldValue(text, selection = TextRange(text.length))) }
    // 外部文本变化时同步；selection 收在末尾、组合区清空，避免指向已不存在的字符。
    LaunchedEffect(text) {
        if (state.value.text != text) {
            state.value = TextFieldValue(text, selection = TextRange(text.length))
        }
    }
    return state
}
