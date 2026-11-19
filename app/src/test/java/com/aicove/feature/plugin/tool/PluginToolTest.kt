package com.aicove.feature.plugin.tool

import com.aicove.feature.agent.domain.tool.ToolCapability
import com.aicove.feature.plugin.domain.PluginInstallService
import com.aicove.feature.plugin.domain.PluginManager
import com.aicove.feature.plugin.pluginTestContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginToolTest {
    @Test
    fun mutatingActionsKeepConfigurationWriteCapabilities() {
        val tool = tool(mockk(), Provider { mockk() })
        for (action in listOf("install", "reload", "enable", "disable", "uninstall")) {
            assertEquals(setOf(ToolCapability.MODIFY_AGENT_CONFIG), tool.effectiveCapabilities(mapOf("action" to JsonPrimitive(action))))
        }
        for (action in listOf("list", "logs")) {
            assertEquals(setOf(ToolCapability.READ_AGENT_CONFIG), tool.effectiveCapabilities(mapOf("action" to JsonPrimitive(action))))
        }
    }

    @Test
    fun listingDoesNotResolveRuntimeOrInstallationDependencies() = runBlocking {
        val manager = mockk<PluginManager>()
        every { manager.ensureLoaded() } returns Unit
        every { manager.plugins } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val installer = mockk<Provider<PluginInstallService>>()
        val tool = tool(manager, installer)
        val result = tool.execute(mapOf("action" to JsonPrimitive("list")))
        assertEquals("plugin", tool.name)
        org.junit.Assert.assertTrue(result is com.aicove.feature.agent.domain.tool.ToolResult.Success)
        verify(exactly = 0) { installer.get() }
    }

    private fun tool(manager: PluginManager, installer: Provider<PluginInstallService>) = PluginTool(
        context = pluginTestContext("zh-CN"), manager = manager, installer = installer,
        providers = Provider { error("unexpected provider access") },
        runtime = Provider { error("unexpected runtime access") },
        terminal = Provider { error("unexpected terminal access") },
        models = Provider { error("unexpected model access") },
        overlays = Provider { error("unexpected overlay access") },
        hooks = Provider { error("unexpected hook access") },
        shizukuPermissions = Provider { error("unexpected Shizuku access") },
        overlayPermissions = Provider { error("unexpected permission access") },
        backupPermissions = Provider { error("unexpected backup access") },
        tips = Provider { error("unexpected tip access") },
        files = Provider { error("unexpected file access") }
    )
}
