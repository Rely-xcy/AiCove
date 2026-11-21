package com.aicove.feature.agent.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageWindowStateTest {

    @Test
    fun searchWindowExpandsForHitAndResetsWhenSessionIsReopened() {
        val state = ChatMessageWindowState()

        assertEquals(30, state.limitFor("session-1"))
        state.includeAtLeast("session-1", 105)
        assertEquals(105, state.limitFor("session-1"))
        assertEquals(30, state.limitFor("session-2"))

        state.reset("session-1")

        assertEquals(30, state.limitFor("session-1"))
    }

    @Test
    fun loadingOlderMessagesExpandsCurrentWindow() {
        val state = ChatMessageWindowState()

        state.loadMore("session-1")
        assertEquals(60, state.limitFor("session-1"))
        state.loadMore("session-1")
        assertEquals(90, state.limitFor("session-1"))
    }
}
