package com.aicove.feature.plugin.presentation

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.lifecycle.viewModelScope
import com.aicove.R
import com.aicove.core.util.FileLogger
import com.aicove.feature.agent.domain.shizuku.ShizukuManager
import com.aicove.feature.agent.domain.shizuku.ShizukuState
import com.aicove.feature.plugin.data.PluginDevSettingsRepository
import com.aicove.feature.plugin.data.PluginRecord
import com.aicove.feature.plugin.data.PluginShizukuPermissionRepository
import com.aicove.feature.plugin.domain.InstalledPlugin
import com.aicove.feature.plugin.domain.PluginContent
import com.aicove.feature.plugin.domain.PluginManager
import com.aicove.feature.plugin.domain.PluginStatus
import com.aicove.feature.plugin.runtime.PluginDependencyResult
import com.aicove.feature.plugin.runtime.PluginProviderRegistrar
import com.aicove.feature.plugin.runtime.PluginTerminalRunner
import com.aicove.feature.plugin.runtime.PluginTipManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginsViewModelTest {
    private val manager = mockk<PluginManager>()
    private val providers = mockk<PluginProviderRegistrar>()
    private val terminal = mockk<PluginTerminalRunner>()
    private val tips = mockk<PluginTipManager>()
    private val permissions = mockk<PluginShizukuPermissionRepository>()
    private val installed = MutableStateFlow(listOf(plugin("first")))
    private lateinit var viewModel: PluginsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(FileLogger)
        every { FileLogger.e(any(), any(), any()) } just runs
        every { manager.plugins } returns installed
        every { manager.isLoaded } returns MutableStateFlow(true)
        every { manager.ensureLoaded() } just runs
        coEvery { providers.sync(any()) } just runs
        coEvery { providers.removeAll(any()) } just runs
        coEvery { permissions.revoke(any()) } just runs
        every { permissions.grants } returns flowOf(emptyMap())
        every { terminal.closePlugin(any()) } just runs
        every { tips.dismissPlugin(any()) } just runs
        val devSettings = mockk<PluginDevSettingsRepository>()
        every { devSettings.enabled } returns flowOf(false)
        val shizuku = mockk<ShizukuManager>()
        every { shizuku.state } returns MutableStateFlow(ShizukuState.NOT_RUNNING)
        val locales = mockk<LocaleList>()
        every { locales[0] } returns Locale.US
        val configuration = mockk<Configuration>()
        every { configuration.locales } returns locales
        val resources = mockk<Resources>()
        every { resources.configuration } returns configuration
        val context = mockk<Context>()
        every { context.resources } returns resources
        viewModel = PluginsViewModel(
            context, manager, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            terminal, providers, permissions, shizuku, mockk(), tips, mockk(), devSettings
        )
    }

    @After
    fun tearDown() {
        try {
            if (::viewModel.isInitialized) runBlocking {
                withTimeout(3_000) {
                    checkNotNull(viewModel.viewModelScope.coroutineContext[Job]).cancelAndJoin()
                }
            }
        } finally {
            Dispatchers.resetMain()
            unmockkObject(FileLogger)
        }
    }

    @Test
    fun uninstall_recordWriteFails_keepsPluginResourcesAndReportsFailure() = runBlocking {
        coEvery { manager.uninstall(ID) } returns false

        viewModel.uninstall(ID)

        assertEquals(R.string.plugins_operation_uninstall_failed, nextMessage())
        awaitIdle()
        assertEquals(ID, installed.value.single().id)
        verify(exactly = 0) { terminal.closePlugin(any()); tips.dismissPlugin(any()) }
        coVerify(exactly = 0) { providers.removeAll(any()); permissions.revoke(any()) }
    }

    @Test
    fun uninstall_cleanupFailsAfterRecordRemoved_stillReleasesResourcesAndAuthorization() = runBlocking {
        coEvery { manager.uninstall(ID) } coAnswers {
            installed.value = emptyList()
            false
        }

        viewModel.uninstall(ID)

        assertEquals(R.string.plugins_operation_uninstall_cleanup_failed, nextMessage())
        awaitIdle()
        verify(exactly = 1) { terminal.closePlugin(ID); tips.dismissPlugin(ID) }
        coVerify(exactly = 1) { providers.removeAll(ID); permissions.revoke(ID) }
    }

    @Test
    fun setEnabled_failure_keepsActiveResourcesAndReportsFailure() = runBlocking {
        coEvery { manager.setEnabled(ID, false) } returns false

        viewModel.setEnabled(ID, false)

        assertEquals(R.string.plugins_operation_toggle_failed, nextMessage())
        awaitIdle()
        assertTrue(installed.value.single().record.enabled)
        verify(exactly = 0) { terminal.closePlugin(any()); tips.dismissPlugin(any()) }
        coVerify(exactly = 0) { providers.removeAll(any()); permissions.revoke(any()) }
    }

    @Test
    fun setEnabled_disabled_releasesResourcesButKeepsAuthorization() = runBlocking {
        coEvery { manager.setEnabled(ID, false) } returns true

        viewModel.setEnabled(ID, false)

        assertEquals(R.string.plugins_operation_disabled, nextMessage())
        awaitIdle()
        verify(exactly = 1) { terminal.closePlugin(ID); tips.dismissPlugin(ID) }
        coVerify(exactly = 1) { providers.removeAll(ID) }
        coVerify(exactly = 0) { permissions.revoke(any()) }
    }

    @Test
    fun setEnabled_ioException_releasesBusyAndReportsFailure() = runBlocking {
        coEvery { manager.setEnabled(ID, true) } throws IOException("registry unavailable")

        viewModel.setEnabled(ID, true)

        assertEquals(R.string.plugins_operation_toggle_failed, nextMessage())
        awaitIdle()
        coVerify(exactly = 0) { providers.sync(any()) }
    }

    @Test
    fun setEnabled_whileBusy_ignoresRepeatedToggleAndUninstall() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { manager.setEnabled(ID, true) } coAnswers {
            entered.complete(Unit)
            release.await()
            true
        }

        viewModel.setEnabled(ID, true)
        assertEquals(setOf(ID), viewModel.busyPluginIds.value)
        withTimeout(3_000) { entered.await() }
        viewModel.setEnabled(ID, false)
        viewModel.uninstall(ID)
        release.complete(Unit)

        assertEquals(R.string.plugins_operation_enabled, nextMessage())
        awaitIdle()
        coVerify(exactly = 1) { manager.setEnabled(ID, any()) }
        coVerify(exactly = 0) { manager.uninstall(any()) }
    }

    @Test
    fun uninstall_whileBusy_ignoresRepeatedUninstallAndToggle() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { manager.uninstall(ID) } coAnswers {
            entered.complete(Unit)
            release.await()
            installed.value = emptyList()
            true
        }

        viewModel.uninstall(ID)
        assertEquals(setOf(ID), viewModel.busyPluginIds.value)
        withTimeout(3_000) { entered.await() }
        viewModel.uninstall(ID)
        viewModel.setEnabled(ID, false)
        release.complete(Unit)

        assertEquals(R.string.plugins_operation_uninstalled, nextMessage())
        awaitIdle()
        coVerify(exactly = 1) { manager.uninstall(ID) }
        coVerify(exactly = 0) { manager.setEnabled(any(), any()) }
    }

    @Test
    fun prepareDependencies_oldInstallationResult_cannotOverwriteNewInstallationState() = runBlocking {
        val oldEntered = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        coEvery { manager.prepareDependencies(ID) } coAnswers {
            if (calls.incrementAndGet() == 1) {
                oldEntered.complete(Unit)
                oldRelease.await()
                PluginDependencyResult.Failed("old installation failed")
            } else {
                newEntered.complete(Unit)
                newRelease.await()
                PluginDependencyResult.Ready("new hash")
            }
        }
        val scopeJob = checkNotNull(viewModel.viewModelScope.coroutineContext[Job])
        val previousJobs = scopeJob.children.toSet()
        viewModel.prepareDependencies(ID)
        withTimeout(3_000) { oldEntered.await() }
        val oldJob = scopeJob.children.first { it !in previousJobs }
        viewModel.prepareDependencies(ID)
        coVerify(exactly = 1) { manager.prepareDependencies(ID) }

        installed.value = listOf(plugin("second"))
        viewModel.prepareDependencies(ID)
        withTimeout(3_000) { newEntered.await() }
        newRelease.complete(Unit)
        val newState = withTimeout(3_000) {
            viewModel.dependencyStates.first { it[ID]?.installRevision == "second" && it[ID]?.ready == true }
        }
        oldRelease.complete(Unit)
        withTimeout(3_000) { oldJob.join() }

        assertEquals(newState, viewModel.dependencyStates.value)
        assertEquals("second", viewModel.dependencyStates.value[ID]?.installRevision)
        coVerify(exactly = 2) { manager.prepareDependencies(ID) }
    }

    @Test
    fun prepareDependencies_ioException_stopsRunningAndAllowsRetry() = runBlocking {
        coEvery { manager.prepareDependencies(ID) } throws IOException("dependency input unreadable")

        viewModel.prepareDependencies(ID)

        val state = withTimeout(3_000) {
            viewModel.dependencyStates.first { it[ID]?.running == false && it[ID]?.ready == false }
        }
        assertFalse(requireNotNull(state[ID]).running)
        assertEquals("first", state[ID]?.installRevision)
        coEvery { manager.prepareDependencies(ID) } returns PluginDependencyResult.Ready("hash")
        viewModel.prepareDependencies(ID)
        withTimeout(3_000) { viewModel.dependencyStates.first { it[ID]?.ready == true } }
        coVerify(exactly = 2) { manager.prepareDependencies(ID) }
    }

    private suspend fun nextMessage(): Int = withTimeout(3_000) { viewModel.operationMessages.first() }

    private suspend fun awaitIdle() {
        withTimeout(3_000) { viewModel.busyPluginIds.first { ID !in it } }
    }

    private companion object {
        const val ID = "community.demo-kit"

        fun plugin(revision: String) = InstalledPlugin(
            record = PluginRecord(
                id = ID,
                version = "1.0.0",
                enabled = true,
                installedAt = 1L,
                origin = "local",
                archiveSha256 = "archive",
                manifestSha256 = "manifest",
                installRevision = revision
            ),
            status = PluginStatus.OK,
            manifest = mockk(),
            content = PluginContent()
        )
    }
}
