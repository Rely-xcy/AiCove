package com.aicove.core.ui

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi

@OptIn(ExperimentalFoundationApi::class)
fun configureComposeSelection() {
    // 1.10.1 的智能选词在内部协程中用过期选区构造系统请求，工具栏兜底无法覆盖。
    ComposeFoundationFlags.isSmartSelectionEnabled = false
}
