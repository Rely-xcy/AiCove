package com.aicove.feature.plugin.runtime

import com.aicove.R
import com.aicove.feature.plugin.domain.LocalizedText
import com.aicove.feature.plugin.domain.PluginRuntime
import com.aicove.feature.plugin.domain.PluginTool
import com.aicove.feature.plugin.pluginTestContext
import kotlinx.serialization.json.put
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginToolAdapterTest {
    private fun tool() = PluginTool(
        pluginId = "community.gateway",
        localId = "connect",
        title = LocalizedText(mapOf("default" to "Connect", "zh-cn" to "连接网关")),
        description = LocalizedText(mapOf("default" to "Connect to the service", "zh-cn" to "连接服务")),
        inputSchema = kotlinx.serialization.json.buildJsonObject { put("type", "object") },
        handler = "connect",
        runtime = mockk<PluginRuntime>(relaxed = true)
    )

    @Test
    fun toolDescriptionAndPermissionSummaryUseAppChineseLocale() = runTest {
        val runtime = mockk<PluginRuntimeManager>()
        val context = pluginTestContext("zh-Hans-CN")
        every { context.getString(R.string.plugin_tool_permission_title) } returns "插件工具授权"
        every { context.getString(R.string.plugin_tool_permission_summary, "网关插件", "连接网关") } returns
            "网关插件 请求：连接网关"
        every {
            context.getString(R.string.plugin_tool_permission_details, "community.gateway", "连接网关", "参数预览")
        } returns "community.gateway/连接网关/参数预览"
        val pluginTool = tool()
        val adapter = PluginToolAdapter(
            context,
            "community.gateway",
            { "网关插件" },
            "1.0.0",
            pluginTool,
            runtime
        ) { true }

        assertEquals("plugin__community_gateway__connect", adapter.name)
        assertEquals("连接服务", adapter.description)
        val englishAdapter = PluginToolAdapter(
            pluginTestContext("en-US"),
            "community.gateway",
            { "Gateway Plugin" },
            "1.0.0",
            pluginTool,
            runtime
        ) { true }
        assertEquals(adapter.name, englishAdapter.name)
        assertEquals("Connect to the service", englishAdapter.description)
        val permission = adapter.buildPermissionRequest("call-1", emptyMap(), "参数预览")
        assertEquals("插件工具授权", permission.title)
        assertEquals("网关插件 请求：连接网关", permission.summary)
        assertEquals("community.gateway/连接网关/参数预览", permission.details)
    }
}
