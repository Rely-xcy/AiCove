package com.aicove.feature.agent.domain.tool.subagent

import android.content.Context
import com.aicove.R
import com.aicove.feature.agent.data.local.dao.AgentMessageDao
import com.aicove.feature.agent.data.local.dao.ChatSessionDao
import com.aicove.feature.agent.data.local.dao.SubagentRunDao
import com.aicove.feature.agent.data.local.entity.ChatSessionEntity
import com.aicove.feature.agent.data.local.entity.SubagentRunEntity
import com.aicove.feature.agent.data.local.entity.SubagentRunStatus
import com.aicove.feature.agent.domain.model.AgentContext
import com.aicove.feature.agent.domain.schedule.WriteLeaseRegistry
import com.aicove.feature.agent.domain.session.SessionUseCase
import com.aicove.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicove.feature.agent.domain.subagent.SubAgentEvent
import com.aicove.feature.agent.domain.subagent.SubAgentEventBus
import com.aicove.feature.agent.domain.subagent.SubAgentEventType
import com.aicove.feature.agent.domain.tool.ToolResult
import com.aicove.feature.settings.data.repository.SubagentTierSettingsRepository
import com.aicove.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskToolResumeTest {
    @Test
    fun sendToInterruptedChildQueuesFreshTurnAndEmitsWakeEventWithoutReplayingOldPrompt() = runBlocking {
        val childId = "child-session"
        val parentId = "parent-session"
        val message = "继续检查未完成的边界情况"
        val bus = SubAgentEventBus()
        val sessionUseCase = mockk<SessionUseCase>()
        val runDao = mockk<SubagentRunDao>()
        val context = mockk<Context>()
        val child = ChatSessionEntity(
            id = childId,
            title = "旧任务",
            createdAt = 1L,
            updatedAt = 2L,
            parentId = parentId
        )
        coEvery { sessionUseCase.getSessionById(childId) } returns child
        coEvery { runDao.getBySubSessionId(childId) } returns SubagentRunEntity(
            subSessionId = childId,
            parentSessionId = parentId,
            status = SubagentRunStatus.INTERRUPTED.name,
            startedAt = 1L,
            updatedAt = 2L,
            terminationReason = "RECOVERY_REQUIRED",
            initialPrompt = null
        )
        coEvery { runDao.resumeFailedRunWithExplicitMessage(childId, parentId, message, any()) } returns
            SubagentRunStatus.QUEUED.name
        every { context.getString(R.string.tool_subagent_message_delivered) } returns
            "消息已提交给子代理消息流程；若子代理不活跃，会排队新轮次。此返回不表示消息已处理或新一轮已运行。"

        val tool = TaskTool(
            sessionUseCase = sessionUseCase,
            chatSessionDao = mockk<ChatSessionDao>(relaxed = true),
            subagentRunDao = runDao,
            agentMessageDao = mockk<AgentMessageDao>(relaxed = true),
            eventBus = bus,
            agentDefinitionRepository = mockk<AgentDefinitionRepository>(relaxed = true),
            aiProviderRepository = mockk<AIProviderRepository>(relaxed = true),
            subagentTierSettings = mockk<SubagentTierSettingsRepository>(relaxed = true),
            writeLeaseRegistry = mockk<WriteLeaseRegistry>(relaxed = true),
            appContext = context
        )
        val wakeEvent = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

        val result = tool.executeWithContext(
            args = mapOf(
                "action" to JsonPrimitive("send"),
                "id" to JsonPrimitive(childId),
                "message" to JsonPrimitive(message)
            ),
            context = AgentContext(
                currentFile = null,
                selectedCode = null,
                projectRoot = "/workspace",
                language = null,
                sessionId = parentId
            )
        )

        val event = wakeEvent.await()
        assertEquals(SubAgentEventType.SPAWNED, event.type)
        assertEquals(childId, event.subSessionId)
        assertEquals("", event.detail) // ViewModel loads the queued prompt from Room, not event memory.
        assertTrue(childId in bus.activeSubSessionIds.value)
        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("queued", data["state"]!!.jsonPrimitive.content)
        assertTrue(data["message"]!!.jsonPrimitive.content.contains("不表示新一轮已经运行"))
        coVerify(exactly = 1) {
            runDao.resumeFailedRunWithExplicitMessage(childId, parentId, message, any())
        }
        coVerify(exactly = 0) { runDao.createQueuedRun(any(), any()) }
    }

    @Test
    fun sendWhileRunningPersistsFollowUpWithoutReplacingCurrentTurnOrEmittingWake() = runBlocking {
        val childId = "running-child"
        val parentId = "parent-session"
        val followUp = "随后再检查这个用例"
        val bus = SubAgentEventBus()
        bus.emit(SubAgentEvent(childId, parentId, SubAgentEventType.SPAWNED))
        val runDao = mockk<SubagentRunDao>()
        val child = ChatSessionEntity(childId, "运行中", 1L, 2L, parentId = parentId)
        coEvery { runDao.getBySubSessionId(childId) } returns SubagentRunEntity(
            subSessionId = childId,
            parentSessionId = parentId,
            status = SubagentRunStatus.RUNNING.name,
            startedAt = 3L,
            updatedAt = 4L,
            claimedPrompt = "current running prompt"
        )
        coEvery {
            runDao.resumeFailedRunWithExplicitMessage(childId, parentId, followUp, any())
        } returns SubagentRunStatus.RUNNING.name
        val sessionUseCase = mockk<SessionUseCase>()
        coEvery { sessionUseCase.getSessionById(childId) } returns child
        val appContext = mockk<Context>()
        every { appContext.getString(R.string.tool_subagent_message_delivered) } returns "Queued, not yet processed."
        val tool = TaskTool(
            sessionUseCase = sessionUseCase,
            chatSessionDao = mockk(relaxed = true),
            subagentRunDao = runDao,
            agentMessageDao = mockk(relaxed = true),
            eventBus = bus,
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            subagentTierSettings = mockk(relaxed = true),
            writeLeaseRegistry = mockk<WriteLeaseRegistry>(relaxed = true),
            appContext = appContext
        )
        val observedEvents = mutableListOf<SubAgentEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { observedEvents += it } }

        val result = tool.executeWithContext(
            args = mapOf(
                "action" to JsonPrimitive("send"),
                "id" to JsonPrimitive(childId),
                "message" to JsonPrimitive(followUp)
            ),
            context = AgentContext(null, null, "/workspace", null, sessionId = parentId)
        )
        collector.cancel()

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("queued", data["state"]!!.jsonPrimitive.content)
        assertTrue("No SPAWNED wake event is needed until current RUNNING turn finishes", observedEvents.isEmpty())
        coVerify(exactly = 1) {
            runDao.resumeFailedRunWithExplicitMessage(childId, parentId, followUp, any())
        }
    }

    @Test
    fun sendToCompletedChildAtCapacityPersistsQueueWithoutOversubscribingOrWaking() = runBlocking {
        val childId = "completed-child"
        val parentId = "parent-session"
        val message = "start a follow-up"
        val bus = SubAgentEventBus()
        repeat(SubAgentEventBus.MAX_RUNNING) { index ->
            bus.emit(SubAgentEvent("other-$index", "other-parent", SubAgentEventType.SPAWNED))
        }
        val runDao = mockk<SubagentRunDao>()
        val child = ChatSessionEntity(childId, "已完成", 1L, 2L, parentId = parentId)
        coEvery { runDao.getBySubSessionId(childId) } returns SubagentRunEntity(
            subSessionId = childId,
            parentSessionId = parentId,
            status = SubagentRunStatus.COMPLETED.name,
            startedAt = 3L,
            updatedAt = 4L
        )
        coEvery { runDao.resumeFailedRunWithExplicitMessage(childId, parentId, message, any()) } returns SubagentRunStatus.QUEUED.name
        val sessionUseCase = mockk<SessionUseCase>()
        coEvery { sessionUseCase.getSessionById(childId) } returns child
        val appContext = mockk<Context>()
        every { appContext.getString(R.string.tool_subagent_message_delivered) } returns "Queued, not yet started."
        val tool = TaskTool(
            sessionUseCase = sessionUseCase,
            chatSessionDao = mockk(relaxed = true),
            subagentRunDao = runDao,
            agentMessageDao = mockk(relaxed = true),
            eventBus = bus,
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            subagentTierSettings = mockk(relaxed = true),
            writeLeaseRegistry = mockk<WriteLeaseRegistry>(relaxed = true),
            appContext = appContext
        )
        val observedEvents = mutableListOf<SubAgentEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { observedEvents += it } }

        val result = tool.executeWithContext(
            args = mapOf(
                "action" to JsonPrimitive("send"),
                "id" to JsonPrimitive(childId),
                "message" to JsonPrimitive(message)
            ),
            context = AgentContext(null, null, "/workspace", null, sessionId = parentId)
        )
        collector.cancel()

        assertTrue(result is ToolResult.Success)
        assertEquals(SubAgentEventBus.MAX_RUNNING, bus.activeCount)
        assertEquals("queued", (result as ToolResult.Success).data.jsonObject["state"]!!.jsonPrimitive.content)
        assertTrue("No wake event when all five slots are occupied", observedEvents.isEmpty())
        coVerify(exactly = 1) { runDao.resumeFailedRunWithExplicitMessage(childId, parentId, message, any()) }
    }

    @Test
    fun listMarksOldChildWithoutRunRowLegacyUnresolved() = runBlocking {
        val parentId = "parent-session"
        val childId = "legacy-child"
        val chatDao = mockk<ChatSessionDao>()
        val runDao = mockk<SubagentRunDao>()
        coEvery { chatDao.getSubSessionsByParentOnce(parentId) } returns listOf(
            ChatSessionEntity(childId, "legacy", 1L, 2L, parentId = parentId)
        )
        coEvery { runDao.getByParentSessionId(parentId) } returns emptyList()
        val tool = TaskTool(
            sessionUseCase = mockk(relaxed = true),
            chatSessionDao = chatDao,
            subagentRunDao = runDao,
            agentMessageDao = mockk(relaxed = true),
            eventBus = SubAgentEventBus(),
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            subagentTierSettings = mockk(relaxed = true),
            writeLeaseRegistry = mockk(relaxed = true),
            appContext = mockk(relaxed = true)
        )

        val result = tool.executeWithContext(
            args = mapOf("action" to JsonPrimitive("list")),
            context = AgentContext(null, null, "/workspace", null, sessionId = parentId)
        )

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("legacy_unresolved", data["subagents"]!!.jsonArray.single().jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals(0, data["runningCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun sendToFailedChildExplicitlyQueuesPreservedFollowUpWithoutReplayingFailedPrompt() = runBlocking {
        val childId = "failed-child"
        val parentId = "parent-session"
        val pending = "already queued follow-up"
        val explicit = "resume after fixing credentials"
        val bus = SubAgentEventBus()
        val runDao = mockk<SubagentRunDao>()
        coEvery { runDao.getBySubSessionId(childId) } returns SubagentRunEntity(
            subSessionId = childId,
            parentSessionId = parentId,
            status = SubagentRunStatus.FAILED.name,
            startedAt = 1L,
            updatedAt = 2L,
            terminationReason = "provider error",
            initialPrompt = pending
        )
        coEvery {
            runDao.resumeFailedRunWithExplicitMessage(childId, parentId, explicit, any())
        } returns SubagentRunStatus.QUEUED.name
        val sessionUseCase = mockk<SessionUseCase>()
        coEvery { sessionUseCase.getSessionById(childId) } returns
            ChatSessionEntity(childId, "failed", 1L, 2L, parentId = parentId)
        val appContext = mockk<Context>()
        every { appContext.getString(R.string.tool_subagent_message_delivered) } returns "Explicit recovery queued; not running yet."
        val tool = TaskTool(
            sessionUseCase = sessionUseCase,
            chatSessionDao = mockk(relaxed = true),
            subagentRunDao = runDao,
            agentMessageDao = mockk(relaxed = true),
            eventBus = bus,
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            subagentTierSettings = mockk(relaxed = true),
            writeLeaseRegistry = mockk<WriteLeaseRegistry>(relaxed = true),
            appContext = appContext
        )
        val wake = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

        val result = tool.executeWithContext(
            args = mapOf(
                "action" to JsonPrimitive("send"),
                "id" to JsonPrimitive(childId),
                "message" to JsonPrimitive(explicit)
            ),
            context = AgentContext(null, null, "/workspace", null, sessionId = parentId)
        )

        assertEquals(SubAgentEventType.SPAWNED, wake.await().type)
        assertTrue(result is ToolResult.Success)
        assertEquals("queued", (result as ToolResult.Success).data.jsonObject["state"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) {
            runDao.resumeFailedRunWithExplicitMessage(childId, parentId, explicit, any())
        }
        coVerify(exactly = 0) { runDao.queueContinuation(childId, parentId, explicit, any()) }
    }

    @Test
    fun sendToInterruptedChildMergesPreservedPendingOnlyAfterExplicitMessage() = runBlocking {
        val childId = "interrupted-with-pending"
        val parentId = "parent-session"
        val explicit = "continue after reviewing the last output"
        val bus = SubAgentEventBus()
        val runDao = mockk<SubagentRunDao>()
        coEvery { runDao.getBySubSessionId(childId) } returns SubagentRunEntity(
            subSessionId = childId,
            parentSessionId = parentId,
            status = SubagentRunStatus.INTERRUPTED.name,
            startedAt = 1L,
            updatedAt = 2L,
            terminationReason = "RECOVERY_REQUIRED",
            claimedPrompt = "interrupted current input",
            initialPrompt = "follow-up that was waiting"
        )
        coEvery {
            runDao.resumeFailedRunWithExplicitMessage(childId, parentId, explicit, any())
        } returns SubagentRunStatus.QUEUED.name
        val sessionUseCase = mockk<SessionUseCase>()
        coEvery { sessionUseCase.getSessionById(childId) } returns
            ChatSessionEntity(childId, "interrupted", 1L, 2L, parentId = parentId)
        val appContext = mockk<Context>()
        every { appContext.getString(R.string.tool_subagent_message_delivered) } returns "Explicit resume queued."
        val tool = TaskTool(
            sessionUseCase = sessionUseCase,
            chatSessionDao = mockk(relaxed = true),
            subagentRunDao = runDao,
            agentMessageDao = mockk(relaxed = true),
            eventBus = bus,
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            subagentTierSettings = mockk(relaxed = true),
            writeLeaseRegistry = mockk<WriteLeaseRegistry>(relaxed = true),
            appContext = appContext
        )
        val wake = async(start = CoroutineStart.UNDISPATCHED) { bus.events.first() }

        val result = tool.executeWithContext(
            args = mapOf(
                "action" to JsonPrimitive("send"),
                "id" to JsonPrimitive(childId),
                "message" to JsonPrimitive(explicit)
            ),
            context = AgentContext(null, null, "/workspace", null, sessionId = parentId)
        )

        assertEquals(SubAgentEventType.SPAWNED, wake.await().type)
        assertTrue(result is ToolResult.Success)
        assertEquals("queued", (result as ToolResult.Success).data.jsonObject["state"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) {
            runDao.resumeFailedRunWithExplicitMessage(childId, parentId, explicit, any())
        }
    }

    @Test
    fun listShowsFailedPendingContinuationAndExplicitSendRecoveryHint() = runBlocking {
        val parentId = "parent-session"
        val childId = "failed-child"
        val chatDao = mockk<ChatSessionDao>()
        val runDao = mockk<SubagentRunDao>()
        coEvery { chatDao.getSubSessionsByParentOnce(parentId) } returns listOf(
            ChatSessionEntity(childId, "failed", 1L, 2L, parentId = parentId)
        )
        coEvery { runDao.getByParentSessionId(parentId) } returns listOf(
            SubagentRunEntity(
                subSessionId = childId,
                parentSessionId = parentId,
                status = SubagentRunStatus.FAILED.name,
                startedAt = 1L,
                updatedAt = 2L,
                terminationReason = "provider error",
                initialPrompt = "preserved follow-up"
            )
        )
        val tool = TaskTool(
            sessionUseCase = mockk(relaxed = true),
            chatSessionDao = chatDao,
            subagentRunDao = runDao,
            agentMessageDao = mockk(relaxed = true),
            eventBus = SubAgentEventBus(),
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            subagentTierSettings = mockk(relaxed = true),
            writeLeaseRegistry = mockk<WriteLeaseRegistry>(relaxed = true),
            appContext = mockk(relaxed = true)
        )

        val result = tool.executeWithContext(
            args = mapOf("action" to JsonPrimitive("list")),
            context = AgentContext(null, null, "/workspace", null, sessionId = parentId)
        )

        assertTrue(result is ToolResult.Success)
        val listed = (result as ToolResult.Success).data.jsonObject["subagents"]!!.jsonArray.single().jsonObject
        assertEquals("failed", listed["state"]!!.jsonPrimitive.content)
        assertTrue(listed["hasPendingContinuation"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(listed["resumeHint"]!!.jsonPrimitive.content.contains("不会自动重跑失败轮"))
    }

    @Test
    fun listFailedRunShowsPreservedContinuationAndManualRecoveryHint() = runBlocking {
        val parentId = "parent-session"
        val childId = "failed-list-child"
        val chatDao = mockk<ChatSessionDao>()
        val runDao = mockk<SubagentRunDao>()
        coEvery { chatDao.getSubSessionsByParentOnce(parentId) } returns listOf(
            ChatSessionEntity(childId, "失败任务", 1L, 2L, parentId = parentId)
        )
        coEvery { runDao.getByParentSessionId(parentId) } returns listOf(
            SubagentRunEntity(
                subSessionId = childId,
                parentSessionId = parentId,
                status = SubagentRunStatus.FAILED.name,
                startedAt = 1L,
                updatedAt = 2L,
                terminationReason = "provider error",
                initialPrompt = "preserved follow-up"
            )
        )
        val tool = TaskTool(
            sessionUseCase = mockk(relaxed = true),
            chatSessionDao = chatDao,
            subagentRunDao = runDao,
            agentMessageDao = mockk(relaxed = true),
            eventBus = SubAgentEventBus(),
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            subagentTierSettings = mockk(relaxed = true),
            writeLeaseRegistry = mockk<WriteLeaseRegistry>(relaxed = true),
            appContext = mockk(relaxed = true)
        )

        val result = tool.executeWithContext(
            args = mapOf("action" to JsonPrimitive("list")),
            context = AgentContext(null, null, "/workspace", null, sessionId = parentId)
        )

        assertTrue(result is ToolResult.Success)
        val listed = (result as ToolResult.Success).data.jsonObject["subagents"]!!.jsonArray.single().jsonObject
        assertEquals("failed", listed["state"]!!.jsonPrimitive.content)
        assertTrue(listed["hasPendingContinuation"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(listed["resumeHint"]!!.jsonPrimitive.content.contains("显式 task(action=\"send\")"))
    }

    @Test
    fun listUsesDurableInterruptedStateEvenIfInMemoryBusLooksActive() = runBlocking {
        val parentId = "parent-session"
        val childId = "interrupted-child"
        val chatDao = mockk<ChatSessionDao>()
        val runDao = mockk<SubagentRunDao>()
        val bus = SubAgentEventBus().apply {
            emit(SubAgentEvent(childId, parentId, SubAgentEventType.SPAWNED))
        }
        coEvery { chatDao.getSubSessionsByParentOnce(parentId) } returns listOf(
            ChatSessionEntity(childId, "interrupted", 1L, 2L, parentId = parentId)
        )
        coEvery { runDao.getByParentSessionId(parentId) } returns listOf(
            SubagentRunEntity(
                subSessionId = childId,
                parentSessionId = parentId,
                status = SubagentRunStatus.INTERRUPTED.name,
                startedAt = 1L,
                updatedAt = 2L,
                terminationReason = "RECOVERY_REQUIRED"
            )
        )
        val tool = TaskTool(
            sessionUseCase = mockk(relaxed = true),
            chatSessionDao = chatDao,
            subagentRunDao = runDao,
            agentMessageDao = mockk(relaxed = true),
            eventBus = bus,
            agentDefinitionRepository = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            subagentTierSettings = mockk(relaxed = true),
            writeLeaseRegistry = mockk(relaxed = true),
            appContext = mockk(relaxed = true)
        )

        val result = tool.executeWithContext(
            args = mapOf("action" to JsonPrimitive("list")),
            context = AgentContext(null, null, "/workspace", null, sessionId = parentId)
        )

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        val listed = data["subagents"]!!.jsonArray.single().jsonObject
        assertEquals("interrupted", listed["state"]!!.jsonPrimitive.content)
        assertEquals(0, data["runningCount"]!!.jsonPrimitive.content.toInt())
    }
}
