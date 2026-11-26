package com.aicove.feature.agent.presentation.component

import com.aicove.feature.agent.presentation.AgentUIMessage
import com.aicove.feature.agent.presentation.MessageRole
import com.aicove.feature.agent.presentation.latestInterruptedAssistantId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterruptedAssistantActionsTest {

    @Test
    fun onlyLastInterruptedAssistantCanBeRegenerated() {
        val previousInterrupted = AgentUIMessage(
            id = "assistant-old",
            role = MessageRole.ASSISTANT,
            content = "old partial"
        ).copy(isInterrupted = true)
        val nextUser = AgentUIMessage(
            id = "user-next",
            role = MessageRole.USER,
            content = "next request"
        )
        val latestInterrupted = AgentUIMessage(
            id = "assistant-latest",
            role = MessageRole.ASSISTANT,
            content = "latest partial"
        ).copy(isInterrupted = true)

        assertEquals(
            "assistant-latest",
            latestInterruptedAssistantId(listOf(previousInterrupted, nextUser, latestInterrupted))
        )
        assertEquals(
            setOf("assistant-old", "assistant-latest"),
            actionableResultIds(listOf(previousInterrupted, nextUser, latestInterrupted), null, false)
        )
        val latestNormal = AgentUIMessage(
            id = "assistant-normal",
            role = MessageRole.ASSISTANT,
            content = "completed answer"
        )
        assertNull(latestInterruptedAssistantId(listOf(previousInterrupted, nextUser, latestNormal)))
        assertNull(latestInterruptedAssistantId(listOf(latestInterrupted)))
    }

    @Test
    fun compactionFailureBeforeOrAfterInterruptedPartialDoesNotHideRegenerationAction() {
        val user = AgentUIMessage("user", MessageRole.USER, "question")
        val failure = AgentUIMessage("compression", MessageRole.TOOL, "compression failed")
            .copy(isCompactionFailure = true, isError = true)
        val interrupted = AgentUIMessage("interrupted", MessageRole.ASSISTANT, "partial")
            .copy(isInterrupted = true)

        assertEquals("interrupted", latestInterruptedAssistantId(listOf(user, failure, interrupted)))
        assertEquals("interrupted", latestInterruptedAssistantId(listOf(user, interrupted, failure)))
        assertEquals(setOf("interrupted"), actionableResultIds(listOf(user, interrupted, failure), null, false))
        assertEquals(
            emptySet<String>(),
            actionableResultIds(listOf(user, interrupted, failure), turnKeyOf(user.id), true)
        )
    }

    @Test
    fun interruptedReasoningWithoutTextKeepsItsActionRowButSystemCardsDoNot() {
        val user = AgentUIMessage("user", MessageRole.USER, "question")
        val reasoning = AgentUIMessage("reasoning", MessageRole.ASSISTANT, "", reasoning = "thinking")
        val interrupted = reasoning.copy(id = "interrupted", isInterrupted = true)
        val summary = AgentUIMessage("summary", MessageRole.ASSISTANT, "summary", isContextSummary = true)

        assertEquals(emptySet<String>(), actionableResultIds(listOf(user, reasoning, summary), null, false))
        assertEquals(setOf(interrupted.id), actionableResultIds(listOf(user, interrupted, summary), null, false))
        assertEquals(emptySet<String>(), actionableResultIds(listOf(user, interrupted), null, true))
    }

    @Test
    fun excludedOldPartialDoesNotHideNewestInterruptedAnswer() {
        val user = AgentUIMessage("user", MessageRole.USER, "question")
        val oldPartial = AgentUIMessage("old", MessageRole.ASSISTANT, "old partial")
            .copy(isInterrupted = true, isContextExcluded = true)
        val latestPartial = AgentUIMessage("latest", MessageRole.ASSISTANT, "new partial")
            .copy(isInterrupted = true)

        assertEquals("latest", latestInterruptedAssistantId(listOf(user, oldPartial, latestPartial)))
    }

    @Test
    fun interruptedAnswerAfterToolSideEffectsHasNoImmediateReplayAction() {
        val user = AgentUIMessage("user", MessageRole.USER, "write file")
        val tool = AgentUIMessage("tool", MessageRole.TOOL, "done", toolName = "writeFile")
        val interrupted = AgentUIMessage("assistant", MessageRole.ASSISTANT, "partial")
            .copy(isInterrupted = true)

        assertNull(latestInterruptedAssistantId(listOf(user, tool, interrupted)))
    }
}
