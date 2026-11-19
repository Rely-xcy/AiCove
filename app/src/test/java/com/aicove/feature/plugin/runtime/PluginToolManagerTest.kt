package com.aicove.feature.plugin.runtime

import com.aicove.feature.plugin.overlay.PluginOverlayManager
import com.aicove.feature.plugin.pluginTestContext
import com.aicove.feature.agent.domain.tool.ToolRegistry
import com.aicove.feature.plugin.data.PluginRecord
import com.aicove.feature.plugin.domain.InstalledPlugin
import com.aicove.feature.plugin.domain.LocalizedText
import com.aicove.feature.plugin.domain.PluginContent
import com.aicove.feature.plugin.domain.PluginManager
import com.aicove.feature.plugin.domain.PluginManifest
import com.aicove.feature.plugin.domain.PluginRuntime
import com.aicove.feature.plugin.domain.PluginStatus
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class PluginToolManagerTest {
    private val runtime = mockk<PluginRuntimeManager>(relaxed = true)
    private val terminalRunner = mockk<PluginTerminalRunner>(relaxed = true)
    private val modelService = mockk<PluginModelService>(relaxed = true)
    private val overlayManager = mockk<PluginOverlayManager>(relaxed = true)
    private val manager = PluginToolManager(
        context = pluginTestContext("zh-Hans-CN"),
        pluginManager = mockk<PluginManager>(),
        toolRegistry = ToolRegistry(),
        runtime = runtime,
        terminalRunner = terminalRunner,
        modelService = modelService,
        overlayManager = overlayManager
    )
    private val syncMethod = PluginToolManager::class.java.getDeclaredMethod("sync", List::class.java)
        .apply { isAccessible = true }
    private val record = PluginRecord(
        id = "demo.runtime",
        version = "1.0.0",
        enabled = true,
        installedAt = 1_000L,
        origin = "local",
        archiveSha256 = "archive-sha256",
        manifestSha256 = "manifest-sha256"
    )

    private fun plugin(record: PluginRecord = this.record, withRuntime: Boolean = true) = InstalledPlugin(
        record = record,
        status = PluginStatus.OK,
        manifest = mockk<PluginManifest> { every { name } returns LocalizedText.single(record.id) },
        content = PluginContent(runtime = if (withRuntime) mockk<PluginRuntime>() else null)
    )

    private fun sync(vararg plugins: InstalledPlugin) {
        syncMethod.invoke(manager, plugins.toList())
    }

    @Test
    fun sync_unchangedInstallationDoesNotCloseRuntime() {
        sync(plugin())
        sync(plugin())
        verify { runtime wasNot Called; terminalRunner wasNot Called }
    }

    @Test
    fun sync_versionChangeClosesOnlyOtherVersionsOnce() {
        sync(plugin())
        val updated = plugin(record.copy(version = "1.1.0"))
        sync(updated)
        sync(updated)
        verify(exactly = 1) { runtime.closeOtherVersions(record.id, "1.1.0") }
        verify(exactly = 0) { runtime.closePlugin(any()) }
        verify(exactly = 1) { terminalRunner.closePlugin(record.id) }
        verify(exactly = 1) { modelService.closePlugin(record.id) }
        verify(exactly = 1) { overlayManager.reloadPlugin(record.id) }
    }

    @Test
    fun sync_sameVersionReinstallWithIdenticalArchiveAndTimestampClosesRuntime() {
        sync(plugin(record.copy(installRevision = "installation-one")))
        val reinstalled = plugin(record.copy(installRevision = "installation-two"))
        sync(reinstalled)
        sync(reinstalled)
        verify(exactly = 1) { runtime.closePlugin(record.id) }
        verify(exactly = 0) { runtime.closeOtherVersions(any(), any()) }
    }

    @Test
    fun sync_legacyRecordReinstallStillReloadsWhenTimestampChanges() {
        sync(plugin())
        sync(plugin(record.copy(installedAt = record.installedAt + 1)))
        verify(exactly = 1) { runtime.closePlugin(record.id) }
    }

    @Test
    fun sync_dependencyStateChangeDoesNotReloadSameInstallation() {
        sync(plugin())
        sync(plugin(record.copy(dependenciesReady = true, dependenciesHash = "dependencies")))
        verify { runtime wasNot Called; terminalRunner wasNot Called }
    }

    @Test
    fun sync_disablingPluginClosesRuntimeAndTerminalOnlyOnce() {
        sync(plugin())
        val disabled = plugin(record.copy(enabled = false))
        sync(disabled)
        sync(disabled)
        verify(exactly = 1) { runtime.closePlugin(record.id) }
        verify(exactly = 1) { terminalRunner.closePlugin(record.id) }
        verify(exactly = 1) { modelService.closePlugin(record.id) }
        verify(exactly = 1) { overlayManager.closePlugin(record.id) }
    }

    @Test
    fun sync_uninstallingPluginWithoutRuntimeStillClosesItsTerminal() {
        sync(plugin(withRuntime = false))
        sync()
        sync()
        verify(exactly = 1) { runtime.closePlugin(record.id) }
        verify(exactly = 1) { terminalRunner.closePlugin(record.id) }
    }

    @Test
    fun sync_otherPluginUpdateLeavesUnchangedPluginAlone() {
        val other = record.copy(id = "other.runtime")
        sync(plugin(), plugin(other))
        sync(plugin(), plugin(other.copy(installRevision = "new-installation")))
        verify(exactly = 1) { runtime.closePlugin(other.id) }
        verify(exactly = 0) { runtime.closePlugin(record.id) }
    }
}
