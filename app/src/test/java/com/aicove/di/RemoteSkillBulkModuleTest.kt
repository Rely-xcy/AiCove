package com.aicove.di

import com.aicove.feature.agent.domain.container.RemoteConnectionConfig
import com.aicove.feature.agent.domain.container.RemoteSshConnection
import com.aicove.feature.settings.data.repository.ExecutionMode
import com.aicove.feature.settings.data.repository.ExecutionModeHolder
import com.aicove.feature.workspace.data.repository.WorkspaceRepository
import com.aicove.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSkillBulkModuleTest {
    private val holder = ExecutionModeHolder().apply { setMode(ExecutionMode.REMOTE_SSH) }
    private val workspace = mockk<WorkspaceRepository>()
    private val connection = mockk<RemoteSshConnection>()
    private var config = RemoteConnectionConfig("server-a", 22, "user", RemoteAuth.Password("test-password"), "/srv/ws")
    private var generation = 1L
    private val source = RemoteSkillBulkModule.provideRemoteSkillBulkContextSource(holder, workspace, connection)

    init {
        every { workspace.currentPathOrNull() } returns "/srv/ws"
        every { connection.config } answers { config }
        every { connection.remoteHome } returns "/home/user"
        every { connection.connectionGeneration } answers { generation }
        every { connection.isConnected() } returns true
    }

    @Test
    fun context_samePathsOnDifferentServersNeverShareCacheKey() {
        val original = source.context()!!
        config = config.copy(host = "server-b")
        val other = source.context()!!
        assertEquals(original.workspaceRoot, other.workspaceRoot)
        assertEquals(original.home, other.home)
        assertNotEquals(original.cacheKey, other.cacheKey)
    }

    @Test
    fun context_reconnectAndDifferentLoginNeverShareCacheKey() {
        val original = source.context()!!.cacheKey
        generation++
        val reconnected = source.context()!!.cacheKey
        config = config.copy(username = "another-user")
        val otherUser = source.context()!!.cacheKey
        config = config.copy(port = 2222)
        assertNotEquals(original, reconnected)
        assertNotEquals(reconnected, otherUser)
        assertNotEquals(otherUser, source.context()!!.cacheKey)
    }

    @Test
    fun context_disconnectedOrChangingConnectionCannotReadCache() {
        every { connection.isConnected() } returns false
        assertNull(source.context())
        every { connection.isConnected() } returns true
        every { connection.connectionGeneration } returnsMany listOf(1L, 2L)
        assertNull(source.context())
    }
}
