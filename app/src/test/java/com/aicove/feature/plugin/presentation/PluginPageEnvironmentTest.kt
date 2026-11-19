package com.aicove.feature.plugin.presentation

import com.aicove.feature.agent.domain.container.RemoteConnectionConfig
import com.aicove.feature.agent.domain.container.RemoteSshConnection
import com.aicove.feature.plugin.domain.PluginFileWatchSource
import com.aicove.feature.settings.data.repository.ExecutionMode
import com.aicove.feature.settings.data.repository.ExecutionModeHolder
import com.aicove.feature.workspace.data.repository.WorkspaceRepository
import com.aicove.feature.workspace.domain.PathHomeResolver
import com.aicove.feature.workspace.domain.WorkspacePathMapper
import com.aicove.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PluginPageEnvironmentTest {
    private val workspace = mockk<WorkspaceRepository>()
    private val mode = ExecutionModeHolder().apply { setMode(ExecutionMode.LOCAL_PROOT) }
    private val connection = mockk<RemoteSshConnection>()
    private val environment = PluginPageEnvironment(
        workspace, mode, connection, mockk<WorkspacePathMapper>(), mockk<PathHomeResolver>(), mockk<PluginFileWatchSource>()
    )

    init {
        every { workspace.current } returns MutableStateFlow(null)
        every { workspace.currentPathOrNull() } returns null
        every { connection.config } returns null
    }

    @Test
    fun unreadyWorkspaceHasAStableEmptyContextInsteadOfThrowing() {
        assertEquals(environment.contextKey(), environment.contextKey())
        assertEquals("workspace", environment.workspaceName())
    }

    @Test
    fun reconnectingToTheSameServerInvalidatesThePreviousContext() {
        mode.setMode(ExecutionMode.REMOTE_SSH)
        every { connection.config } returns RemoteConnectionConfig("example.invalid", 22, "user", RemoteAuth.Password("synthetic"), "/srv/ws")
        every { workspace.currentPathOrNull() } returns "/srv/ws/demo"
        every { connection.connectionGeneration } returns 1L
        val first = environment.contextKey()
        every { connection.connectionGeneration } returns 2L
        assertNotEquals(first, environment.contextKey())
    }
}
