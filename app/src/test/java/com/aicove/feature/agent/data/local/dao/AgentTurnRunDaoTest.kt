package com.aicove.feature.agent.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicove.feature.agent.data.local.database.AgentDatabase
import com.aicove.feature.agent.data.local.entity.AgentMessageEntity
import com.aicove.feature.agent.data.local.entity.AgentTurnRunEntity
import com.aicove.feature.agent.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentTurnRunDaoTest {
    private lateinit var database: AgentDatabase
    private lateinit var sessions: ChatSessionDao
    private lateinit var messages: AgentMessageDao
    private lateinit var runs: AgentTurnRunDao

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue(
                "Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）",
                !androidContainer
            )
        }
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AgentDatabase::class.java
        ).build()
        sessions = database.chatSessionDao()
        messages = database.agentMessageDao()
        runs = database.agentTurnRunDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun runningRowsBecomeInterruptedAndOnlyInterruptedRowsCanBeClaimed() = runBlocking {
        createRun("stale-run", AgentTurnRunEntity.RUNNING)
        createRun("other-running-run", AgentTurnRunEntity.RUNNING)
        createRun("completed-run", AgentTurnRunEntity.COMPLETED)
        createRun("failed-run", AgentTurnRunEntity.FAILED)
        createRun("cancelled-run", AgentTurnRunEntity.CANCELLED)

        assertNull(runs.getInterrupted("stale-run"))
        assertEquals(
            "a RUNNING row is not claimable",
            0,
            runs.claimInterrupted("stale-run", messageId("stale-run"), now = 20L)
        )
        assertEquals(2, runs.interruptStaleRuns(now = 30L))

        val interrupted = runs.getInterrupted("stale-run")!!
        assertEquals(AgentTurnRunEntity.INTERRUPTED, interrupted.status)
        assertEquals(30L, interrupted.updatedAt)
        assertEquals(AgentTurnRunEntity.INTERRUPTED, runs.getInterrupted("other-running-run")?.status)
        assertNull(runs.getInterrupted("completed-run"))
        assertNull(runs.getInterrupted("failed-run"))
        assertNull(runs.getInterrupted("cancelled-run"))
        for (sessionId in listOf("completed-run", "failed-run", "cancelled-run")) {
            assertEquals(
                "$sessionId is not interrupted and cannot be claimed",
                0,
                runs.claimInterrupted(sessionId, messageId(sessionId), now = 35L)
            )
        }

        assertEquals(
            1,
            runs.claimInterrupted("stale-run", messageId("stale-run"), now = 40L)
        )
        val claimed = runs.observeBySessionId("stale-run").first()!!
        assertEquals(AgentTurnRunEntity.RUNNING, claimed.status)
        assertEquals(40L, claimed.startedAt)
        assertEquals(40L, claimed.updatedAt)
        assertEquals(
            "CAS prevents a second consumer from claiming the row",
            0,
            runs.claimInterrupted("stale-run", messageId("stale-run"), now = 50L)
        )
        assertNull(runs.getInterrupted("stale-run"))
        assertEquals(AgentTurnRunEntity.COMPLETED, runs.observeBySessionId("completed-run").first()?.status)
        assertEquals(AgentTurnRunEntity.FAILED, runs.observeBySessionId("failed-run").first()?.status)
        assertEquals(AgentTurnRunEntity.CANCELLED, runs.observeBySessionId("cancelled-run").first()?.status)
    }

    @Test
    fun runningRowsCanCompleteFailAndCancelOnlyOnce() = runBlocking {
        createRun("complete-run", AgentTurnRunEntity.RUNNING)
        createRun("fail-run", AgentTurnRunEntity.RUNNING)
        createRun("cancel-one-run", AgentTurnRunEntity.RUNNING)
        createRun("cancel-bulk-run", AgentTurnRunEntity.RUNNING)
        createRun("already-completed-run", AgentTurnRunEntity.COMPLETED)

        assertEquals(
            1,
            runs.markCompleted("complete-run", messageId("complete-run"), now = 60L)
        )
        assertEquals(AgentTurnRunEntity.COMPLETED, runs.observeBySessionId("complete-run").first()?.status)
        assertEquals(
            "terminal rows cannot be failed later",
            0,
            runs.markFailed("complete-run", messageId("complete-run"), now = 61L)
        )

        assertEquals(
            1,
            runs.markFailed("fail-run", messageId("fail-run"), now = 70L)
        )
        assertEquals(AgentTurnRunEntity.FAILED, runs.observeBySessionId("fail-run").first()?.status)
        assertEquals(
            "only the matching user message can finish this run",
            0,
            runs.markCompleted("fail-run", "wrong-user-message", now = 71L)
        )

        assertEquals(
            1,
            runs.markCancelled("cancel-one-run", messageId("cancel-one-run"), now = 80L)
        )
        assertEquals(AgentTurnRunEntity.CANCELLED, runs.observeBySessionId("cancel-one-run").first()?.status)
        assertEquals(
            "the individual cancellation is terminal",
            0,
            runs.markCancelled("cancel-one-run", messageId("cancel-one-run"), now = 81L)
        )

        assertEquals(1, runs.cancelRunningRuns(now = 90L))
        assertEquals(AgentTurnRunEntity.CANCELLED, runs.observeBySessionId("cancel-bulk-run").first()?.status)
        assertEquals(AgentTurnRunEntity.COMPLETED, runs.observeBySessionId("already-completed-run").first()?.status)
    }

    @Test
    fun deletingSessionOrUserMessageCascadesRunRows() = runBlocking {
        createRun("delete-session-run", AgentTurnRunEntity.RUNNING)
        createRun("delete-message-run", AgentTurnRunEntity.INTERRUPTED)

        sessions.delete("delete-session-run")
        assertNull(runs.observeBySessionId("delete-session-run").first())

        messages.deleteMessageById(messageId("delete-message-run"))
        assertNull(runs.observeBySessionId("delete-message-run").first())
    }

    private suspend fun createRun(sessionId: String, status: String) {
        sessions.upsert(
            ChatSessionEntity(
                id = sessionId,
                title = sessionId,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        messages.insert(
            AgentMessageEntity(
                id = messageId(sessionId),
                sessionId = sessionId,
                role = "USER",
                content = "input for $sessionId",
                timestamp = 2L
            )
        )
        runs.upsert(
            AgentTurnRunEntity(
                sessionId = sessionId,
                userMessageId = messageId(sessionId),
                status = status,
                startedAt = 3L,
                updatedAt = 4L
            )
        )
    }

    private fun messageId(sessionId: String) = "message-$sessionId"
}
