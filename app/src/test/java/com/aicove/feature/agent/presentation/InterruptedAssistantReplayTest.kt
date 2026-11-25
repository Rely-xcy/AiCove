package com.aicove.feature.agent.presentation

import com.aicove.feature.agent.data.local.dao.AgentMessageDao
import com.aicove.feature.agent.data.local.entity.AgentMessageEntity
import com.aicove.feature.agent.domain.model.AgentImage
import com.aicove.feature.agent.domain.model.AgentMessage
import com.aicove.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicove.feature.agent.domain.session.SessionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedAssistantReplayTest {
    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val replay = InterruptedAssistantReplay(dao, persistence)

    @Test
    fun plainTextTurnReplaysOriginalRequestWithoutAttachments() = kotlinx.coroutines.runBlocking {
        val user = userRow(content = "fix the parser")
        val partial = partialRow()
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, partial)
        coEvery { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) } returns listOf(
            AgentMessage.UserMessage(user.id, user.content)
        )

        val result = replay.prepare(partial, { WORKSPACE }) { false } as InterruptedAssistantReplay.Preparation.Ready

        assertEquals("fix the parser", result.request.request)
        assertEquals("fix the parser", result.request.modelRequest)
        assertTrue(result.request.inputImages.isEmpty())
        assertEquals(user.id, result.request.userMessageId)
        coVerify(exactly = 1) { dao.insert(match { it.id == partial.id && it.isContextExcluded }) }
    }

    @Test
    fun attachmentTurnPreservesOriginalRequestImagesAndPartial() = kotlinx.coroutines.runBlocking {
        val user = userRow()
        val partial = partialRow()
        val image = AgentImage("image/png", "base64-image", "~/workspace/image.png")
        val attachmentDescription = "describe this image\n\n附件：\n- notes.txt：~/workspace/notes.txt"
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, partial)
        coEvery { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) } returns listOf(
            AgentMessage.UserMessage(user.id, attachmentDescription, listOf(image)),
            AgentMessage.AssistantMessage(partial.id, "partial answer")
        )

        val result = replay.prepare(partial, { WORKSPACE }) { false } as InterruptedAssistantReplay.Preparation.Ready

        assertEquals("原用户消息文本不变", user.content, result.request.request)
        assertEquals("附件说明留在模型请求中", attachmentDescription, result.request.modelRequest)
        assertEquals(listOf(image), result.request.inputImages)
        assertEquals(user.id, result.request.userMessageId)
        assertEquals(SID, result.request.sessionId)
        val savedPartial = slot<AgentMessageEntity>()
        coVerify(exactly = 1) { dao.insert(capture(savedPartial)) }
        assertEquals(partial, savedPartial.captured.copy(isContextExcluded = false))
        assertTrue(savedPartial.captured.isContextExcluded)
        coVerify(exactly = 0) { dao.deleteMessageById(partial.id) }
        coVerify(exactly = 1) { persistence.evictHistory(SID) }
    }

    @Test
    fun compactionFailureAroundPartialDoesNotCountAsToolSideEffect() = kotlinx.coroutines.runBlocking {
        val user = userRow()
        val failure = AgentMessageEntity(
            "compression", SID, MessageRole.TOOL.name, "compression failed", 2,
            toolName = COMPACTION_FAILURE_TOOL_NAME, isError = true
        )
        val partial = partialRow()
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, failure, partial)
        coEvery { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) } returns listOf(
            AgentMessage.UserMessage(user.id, user.content)
        )

        val result = replay.prepare(partial, { WORKSPACE }) { false }

        assertTrue(result is InterruptedAssistantReplay.Preparation.Ready)
        coVerify(exactly = 1) { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) }
    }

    @Test
    fun trailingCompactionFailureDoesNotMakeInterruptedPartialStale() = kotlinx.coroutines.runBlocking {
        val user = userRow()
        val partial = partialRow()
        val failure = AgentMessageEntity(
            "compression", SID, MessageRole.TOOL.name, "compression failed", 3,
            toolName = COMPACTION_FAILURE_TOOL_NAME, isError = true
        )
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, partial, failure)
        coEvery { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) } returns listOf(
            AgentMessage.UserMessage(user.id, user.content)
        )

        val result = replay.prepare(partial, { WORKSPACE }) { false }

        assertTrue(result is InterruptedAssistantReplay.Preparation.Ready)
        coVerify(exactly = 1) { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) }
    }

    @Test
    fun toolSideEffectTurnIsUnavailableWithoutChangingRowsOrHistory() = kotlinx.coroutines.runBlocking {
        val user = userRow()
        val tool = AgentMessageEntity("tool-1", SID, MessageRole.TOOL.name, "file written", 2, toolCallId = "call-1")
        val partial = partialRow()
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, tool, partial)

        val result = replay.prepare(partial, { WORKSPACE }) { false }

        assertEquals(InterruptedAssistantReplay.Preparation.Unavailable, result)
        coVerify(exactly = 0) { persistence.buildHistory(any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { persistence.evictHistory(any()) }
    }

    @Test
    fun intermediateAssistantTurnIsUnavailableWithoutChangingRowsOrHistory() = kotlinx.coroutines.runBlocking {
        val user = userRow()
        val intermediate = AgentMessageEntity("assistant-mid", SID, MessageRole.ASSISTANT.name, "working", 2)
        val partial = partialRow()
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, intermediate, partial)

        val result = replay.prepare(partial, { WORKSPACE }) { false }

        assertEquals(InterruptedAssistantReplay.Preparation.Unavailable, result)
        coVerify(exactly = 0) { persistence.buildHistory(any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun deletedSessionIsIgnoredWithoutBuildingHistoryOrExcludingPartial() = kotlinx.coroutines.runBlocking {
        val user = userRow()
        val partial = partialRow()
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, partial)

        val result = replay.prepare(partial, { null }) { false }

        assertEquals(InterruptedAssistantReplay.Preparation.Ignored, result)
        coVerify(exactly = 0) { persistence.buildHistory(any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { persistence.evictHistory(any()) }
    }

    @Test
    fun oldInterruptedAssistantIsIgnoredWhenSessionHasNewerConversation() = kotlinx.coroutines.runBlocking {
        val user = userRow()
        val partial = partialRow()
        val newerUser = AgentMessageEntity("user-new", SID, MessageRole.USER.name, "new question", 3)
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, partial, newerUser)

        val result = replay.prepare(partial, { WORKSPACE }) { false }

        assertEquals(InterruptedAssistantReplay.Preparation.Ignored, result)
        coVerify(exactly = 0) { persistence.buildHistory(any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun trailingBackgroundNotificationKeepsLatestInterruptedAssistantReplayable() = kotlinx.coroutines.runBlocking {
        val user = userRow()
        val partial = partialRow()
        val notification = AgentMessageEntity(
            "background-notification",
            SID,
            MessageRole.USER.name,
            "$BACKGROUND_NOTIFICATION_PREFIX task completed",
            3
        )
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, partial, notification)
        coEvery { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) } returns listOf(
            AgentMessage.UserMessage(user.id, user.content)
        )

        val result = replay.prepare(partial, { WORKSPACE }) { false }

        assertTrue(result is InterruptedAssistantReplay.Preparation.Ready)
        coVerify(exactly = 1) { dao.insert(match { it.id == partial.id && it.isContextExcluded }) }
        assertEquals(
            partial.id,
            latestInterruptedAssistantId(
                listOf(
                    AgentUIMessage(user.id, MessageRole.USER, user.content),
                    AgentUIMessage(partial.id, MessageRole.ASSISTANT, partial.content).copy(isInterrupted = true),
                    AgentUIMessage(notification.id, MessageRole.USER, notification.content)
                        .copy(isBackgroundNotification = true)
                )
            )
        )
    }

    @Test
    fun missingOriginalUserRowIsUnavailableWithoutBuildingHistoryOrExcludingPartial() = kotlinx.coroutines.runBlocking {
        val partial = partialRow()
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(partial)

        val result = replay.prepare(partial, { WORKSPACE }) { false }

        assertEquals(InterruptedAssistantReplay.Preparation.Unavailable, result)
        coVerify(exactly = 0) { persistence.buildHistory(any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { persistence.evictHistory(any()) }
    }

    @Test
    fun missingOriginalUserInReplayHistoryIsUnavailableWithoutExcludingPartial() = kotlinx.coroutines.runBlocking {
        val user = userRow()
        val partial = partialRow()
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(user, partial)
        coEvery { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) } returns emptyList()

        val result = replay.prepare(partial, { WORKSPACE }) { false }

        assertEquals(InterruptedAssistantReplay.Preparation.Unavailable, result)
        coVerify(exactly = 1) { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) }
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { persistence.evictHistory(any()) }
    }

    @Test
    fun slashCommandTurnIsUnavailable() = kotlinx.coroutines.runBlocking {
        val commandUser = userRow(content = "/compress")
        val partial = partialRow()
        coEvery { dao.getMessagesBySessionOnce(SID) } returns listOf(commandUser, partial)

        val result = replay.prepare(partial, { WORKSPACE }) { it == "/compress" }

        assertEquals(InterruptedAssistantReplay.Preparation.Unavailable, result)
        coVerify(exactly = 0) { persistence.buildHistory(any(), any()) }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun executionHistoryEndsBeforeReplayUserMessage() = kotlinx.coroutines.runBlocking {
        val earlierUser = AgentMessage.UserMessage("user-before", "earlier")
        val originalUser = AgentMessage.UserMessage("user-original", "replay")
        val replayedPartial = AgentMessage.AssistantMessage("assistant-old", "partial")
        val history = listOf(earlierUser, originalUser, replayedPartial)
        coEvery { persistence.buildHistory(SID, SessionUseCase.PENDING_TOOL_MARKER) } returns history

        val result = replay.buildExecutionHistory(SID, originalUser.id, requestChars = 6, attachmentCount = 0)

        assertEquals(listOf(earlierUser), result?.requestHistory)
        assertEquals(history, result?.history)
        assertEquals(originalUser, result?.replayUserMessage)
        assertFalse(result?.requestHistory?.any { it.id == replayedPartial.id } ?: true)
    }

    private fun userRow(content: String = "describe this image") = AgentMessageEntity(
        id = "user-original",
        sessionId = SID,
        role = MessageRole.USER.name,
        content = content,
        timestamp = 1
    )

    private fun partialRow() = AgentMessageEntity(
        id = "assistant-partial",
        sessionId = SID,
        role = MessageRole.ASSISTANT.name,
        content = "partial answer",
        timestamp = 2,
        isError = true
    )

    private companion object {
        const val SID = "session-replay"
        const val WORKSPACE = "/workspace"
    }
}
