package com.aicove.core.plugin

/**
 * 宿主 UI 槽位里的一条插件入口。
 *
 * 放在 core 是为了让侧栏、顶栏这类 agent 层组件能渲染插件入口，而不必 import
 * feature/plugin——两个 feature 互相依赖会成环，拆分层就失效了。
 */
data class PluginSlotEntry(
    val pluginId: String,
    val localId: String,
    val title: String,
    val icon: String,
    val slot: String = "",
    val kind: String = "page",
    val confirm: Boolean = false
) {
    val key: String get() = "$pluginId:$localId"
}
