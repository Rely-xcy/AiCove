package com.aicove.feature.workspace.domain.remote.agent

import com.aicove.feature.agent.domain.container.RemoteSshConnection
import com.aicove.feature.workspace.domain.FileAccessProvider
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteAgentHolderConnectionTest {
    private val connection = mockk<RemoteSshConnection>(relaxed = true)
    private val firstSession = mockk<RemoteAgentSession>(relaxed = true)
    private val secondSession = mockk<RemoteAgentSession>(relaxed = true)
    private var generation = 1L
    private lateinit var holder: RemoteAgentHolder

    @Before
    fun setUp() {
        mockkConstructor(RemoteAgentLauncher::class)
        every { connection.isConnected() } returns true
        every { connection.connectionGeneration } answers { generation }
        every { connection.startExecSession(any()).inputStream } answers {
            ByteArrayInputStream("/home/user\n".toByteArray())
        }
        val installation = RemoteAgentInstallation("/home/user/agent", RemoteAgentArch.entries.first(), 1, false)
        coEvery { anyConstructed<RemoteAgentLauncher>().connect() } returnsMany listOf(
            RemoteAgentConnection(firstSession, installation), RemoteAgentConnection(secondSession, installation)
        )
        holder = RemoteAgentHolder(connection, mockk(), Lazy { mockk<FileAccessProvider>() })
    }

    @After
    fun tearDown() {
        if (::holder.isInitialized) holder.reset()
        unmockkConstructor(RemoteAgentLauncher::class)
    }

    @Test
    fun writeForConnection_rejectsSessionFromPreviousConnection() = runBlocking {
        assertTrue(holder.ensureConnected())
        generation++
        try {
            holder.writeForConnection(2L, "/home/user/staged.md", byteArrayOf(1), false, true)
            throw AssertionError("Old agent session accepted a new SSH connection")
        } catch (_: RemoteAgentUnavailableException) {
        }
        coVerify(exactly = 0) { firstSession.request(any(), any(), any()) }
    }

    @Test
    fun writeForConnection_inFlightWriteNeverSwitchesToNewSession() = runBlocking {
        assertTrue(holder.ensureConnected())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { firstSession.request("write", any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            AgentResponse(id = 1, ok = true)
        }
        val writing = async(start = CoroutineStart.UNDISPATCHED) {
            holder.writeForConnection(1L, "/home/user/staged.md", byteArrayOf(1), false, true)
        }
        try {
            started.await()
            generation++
            holder.reset()
            assertTrue(holder.ensureConnected())
        } finally {
            release.complete(Unit)
        }
        writing.await()
        coVerify(exactly = 1) { firstSession.request("write", any(), any()) }
        coVerify(exactly = 0) { secondSession.request(any(), any(), any()) }
    }
}
