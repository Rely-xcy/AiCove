package com.aicove.feature.agent.presentation.component

import com.aicove.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BusyLabelResTest {
    @Test
    fun preparationWinsOverAnOlderToolName() {
        assertEquals(R.string.chat_status_preparing_request, busyLabelRes("readFile", true))
    }

    @Test
    fun aRequestWithoutToolOutputUsesThinking() {
        assertEquals(R.string.chat_status_thinking, busyLabelRes(null, false))
    }

    @Test
    fun anActiveToolKeepsItsOwnLabel() {
        assertEquals(R.string.chat_status_reading_file, busyLabelRes("readFile", false))
    }
}
