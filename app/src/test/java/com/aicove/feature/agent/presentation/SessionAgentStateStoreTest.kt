package com.aicove.feature.agent.presentation

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAgentStateStoreTest {
    @Test
    fun cancellationClearsOnlyThePreparingSession() {
        val store = SessionAgentStateStore()
        val job = Job()
        store.prepare("first", job) { it.message.orEmpty() }
        store.set("second", AgentUIState.Streaming)

        assertTrue(store["first"] is AgentUIState.Preparing)
        job.cancel()

        assertSame(AgentUIState.Idle, store["first"])
        assertSame(AgentUIState.Streaming, store["second"])
    }

    @Test
    fun olderCompletionCannotClearItsReplacement() {
        val store = SessionAgentStateStore()
        val oldJob = Job()
        val newJob = Job()
        store.prepare("session", oldJob) { it.message.orEmpty() }
        store.prepare("session", newJob) { it.message.orEmpty() }
        val replacement = store["session"]

        oldJob.cancel()

        assertSame(replacement, store["session"])
        assertTrue(store["session"] is AgentUIState.Preparing)
        newJob.cancel()
    }

    @Test
    fun successfulHandoffKeepsTheTerminalState() {
        val store = SessionAgentStateStore()
        val job = Job()
        store.prepare("session", job) { it.message.orEmpty() }
        store.set("session", AgentUIState.Result)

        job.complete()

        assertSame(AgentUIState.Result, store["session"])
    }

    @Test
    fun failedPreparationPublishesError() {
        val store = SessionAgentStateStore()
        val job = Job()
        store.prepare("session", job) { it.message.orEmpty() }

        job.completeExceptionally(IllegalStateException("failed"))

        assertEquals("failed", (store["session"] as AgentUIState.Error).message)
    }

    @Test
    fun resetAndRemovalDoNotLeavePreparingSessions() {
        val store = SessionAgentStateStore()
        val job = Job()
        store.prepare("session", job) { it.message.orEmpty() }
        store.reset()
        job.cancel()
        assertSame(AgentUIState.Idle, store["session"])

        store.remove("session")
        assertTrue(store.states.value.isEmpty())
    }
}
