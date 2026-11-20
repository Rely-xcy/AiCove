package com.aicove.feature.agent.presentation.component

import com.aicove.feature.agent.presentation.AgentAttachment
import com.aicove.feature.agent.presentation.AgentUIMessage
import com.aicove.feature.agent.presentation.MessageRole
import com.aicove.feature.agent.presentation.PendingUserMessage
import com.aicove.feature.agent.presentation.PendingUserMessageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUserMessageTest {

    private fun pending(id: String, text: String = "hi") = PendingUserMessage(id = id, text = text)

    @Test
    fun bubbleStaysUntilAnySignalArrives() {
        // 直发但还没落库：气泡必须还在，否则「乐观上屏」等于没做
        val resolving = listOf(pending("m1"))
        assertEquals(resolving, resolvePendingUserMessages(resolving, emptySet(), emptySet(), emptySet()))
    }

    @Test
    fun directSend_bubbleRetiresWhenItsRowLands() {
        // 直发路径：落库行 id 与气泡 id 相同 → 气泡退场，由列表项承载（同一条不同时显示两遍）
        val resolving = listOf(pending("m1"))
        assertTrue(resolvePendingUserMessages(resolving, setOf("m1"), emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun queuedSend_bubbleRetiresToQueuePanel() {
        // 入队路径：VM 把它排进队列（气泡与队列面板不能同显同一条）→ 改由输入框上方的队列面板承载
        val resolving = listOf(pending("m1"))
        assertTrue(
            resolvePendingUserMessages(resolving, emptySet(), setOf("m1"), emptySet()).isEmpty()
        )
    }

    @Test
    fun queueDelete_bubbleRetiresOnDiscardedSignal() {
        // 丢弃路径一（队列面板删除，AIAgentViewModel.removeQueuedRequest）：既不会落库、也不在队列里，
        // 只有「VM 明说这条作废」才能让它退场
        val resolving = listOf(pending("m1"))
        assertTrue(
            resolvePendingUserMessages(resolving, emptySet(), emptySet(), setOf("m1")).isEmpty()
        )
    }

    @Test
    fun interject_bubbleRetiresOnDiscardedSignal() {
        // 插话以原 id 落库，但不作为独立用户气泡渲染。
        val resolving = listOf(pending("m1"))
        assertTrue(
            resolvePendingUserMessages(resolving, emptySet(), emptySet(), setOf("m1")).isEmpty()
        )
    }

    @Test
    fun rewind_bubbleRetiresOnDiscardedSignal() {
        // 丢弃路径三（回退清空队列，executeRewindOption）
        val resolving = listOf(pending("m1"), pending("m2", "另一条"))
        val kept = resolvePendingUserMessages(resolving, emptySet(), emptySet(), setOf("m1"))
        assertEquals(listOf("m2"), kept.map { it.id })
    }

    @Test
    fun stopAll_bubbleRetiresOnDiscardedSignal() {
        // 丢弃路径四（切换工作区停止全部，stopAllAgents）
        val resolving = listOf(pending("m1"))
        assertTrue(
            resolvePendingUserMessages(resolving, emptySet(), emptySet(), setOf("m1")).isEmpty()
        )
    }

    @Test
    fun normalSend_stillRetiresThroughTheSameThreeSignals() {
        // 正常发送仍然退场：作废台账没记它，但它会依次落到「入队 → 落库」两个信号上，不会被台账影响
        val resolving = listOf(pending("m1"))
        assertTrue(resolvePendingUserMessages(resolving, emptySet(), setOf("m1"), emptySet()).isEmpty())
        assertTrue(resolvePendingUserMessages(resolving, setOf("m1"), emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun sameTextSentTwice_isMatchedByIdNotText() {
        // 同一段文本连发两条：第一条已落库、第二条仍在 pending（或已入队）。
        // 按文本比对时第一条会把第二条一起退场（第二条就此消失），按 id 比对不会。
        val resolving = listOf(pending("m1", "再跑一次"), pending("m2", "再跑一次"))
        val kept = resolvePendingUserMessages(resolving, setOf("m1"), emptySet(), emptySet())
        assertEquals(listOf("m2"), kept.map { it.id })
    }

    @Test
    fun queuedEntryClaimsOnlyItsOwnId() {
        // 队列里那条的 clientMessageId 只认领它自己，同文本的另一条照旧留在气泡里
        val resolving = listOf(pending("m1", "再跑一次"), pending("m2", "再跑一次"))
        val kept = resolvePendingUserMessages(resolving, emptySet(), setOf("m2"), emptySet())
        assertEquals(listOf("m1"), kept.map { it.id })
    }

    @Test
    fun databaseRowDoesNotRetireBubbleBeforeTheAsyncFrameDisplaysIt() {
        val store = PendingUserMessageStore()
        val message = AgentUIMessage(id = "m1", role = MessageRole.USER, content = "hi")
        store.sending("session", pending("m1"))
        val previousFrame = flattenChatProcesses(buildChatItems(emptyList(), emptyMap()))
        val databaseMessages = listOf(message)

        assertEquals("m1", databaseMessages.single().id)
        store.acknowledge("session", renderedUserMessageIds(previousFrame))
        assertEquals(listOf("m1"), store["session"].map { it.id })

        val nextFrame = flattenChatProcesses(buildChatItems(databaseMessages, emptyMap()))
        store.acknowledge("session", renderedUserMessageIds(nextFrame))
        assertTrue(store["session"].isEmpty())
    }

    @Test
    fun turnHeaderCannotClaimTheUserBubble() {
        val messages = listOf(
            AgentUIMessage(id = "m1", role = MessageRole.USER, content = "hi"),
            AgentUIMessage(id = "t1", role = MessageRole.TOOL, content = "done")
        )
        val headers = buildChatItems(messages, emptyMap()).filter { it.turnHeader != null }
        assertTrue(headers.isNotEmpty())
        assertTrue(renderedUserMessageIds(headers).isEmpty())
    }

    @Test
    fun consumedRequestKeepsItsEditedTextAndAttachmentsAcrossSessionChanges() {
        val store = PendingUserMessageStore()
        val attachment = AgentAttachment("note.txt", "/workspace/note.txt", "/local/note.txt", "text/plain", 2L, false)
        val edited = PendingUserMessage("m1", "edited", listOf(attachment))
        store.sending("session", edited)

        assertTrue(store["other-session"].isEmpty())
        assertEquals(listOf(edited), store["session"])
        assertEquals(listOf(edited), resolvePendingUserMessages(store["session"], emptySet(), emptySet(), emptySet()))
        store.acknowledge("other-session", setOf("m1"))
        assertEquals(listOf(edited), store["session"])
        store.acknowledge("session", setOf("m1"))
        assertTrue(store["session"].isEmpty())
    }

    @Test
    fun queuePanelHidesTheBubbleWithoutDestroyingItsHandoffState() {
        val store = PendingUserMessageStore()
        store.sending("session", pending("m1"))
        assertTrue(resolvePendingUserMessages(store["session"], emptySet(), setOf("m1"), emptySet()).isEmpty())
        assertEquals(listOf("m1"), store["session"].map { it.id })
        assertEquals(listOf("m1"), resolvePendingUserMessages(store["session"], emptySet(), emptySet(), emptySet()).map { it.id })
    }

    @Test
    fun returningToTheLatestPageDoesNotReplayOlderPersistedUsersAtTheTail() {
        val store = PendingUserMessageStore()
        store.sending("session", pending("older"))
        store.persisted("session", "older", 100L)
        store.sending("session", pending("still-sending"))
        val latestPage = flattenChatProcesses(buildChatItems(listOf(
            AgentUIMessage(id = "newer", role = MessageRole.USER, content = "next", timestamp = 200L)
        ), emptyMap()))
        store.acknowledge("session", settledUserMessageIds(store["session"], latestPage))
        assertEquals(listOf("still-sending"), store["session"].map { it.id })
    }

    @Test
    fun anOldAsyncFrameCannotRetireTheNewlyPersistedUser() {
        val store = PendingUserMessageStore()
        store.sending("session", pending("new-user"))
        store.persisted("session", "new-user", 200L)
        val previousFrame = flattenChatProcesses(buildChatItems(listOf(
            AgentUIMessage(id = "older", role = MessageRole.USER, content = "previous", timestamp = 100L)
        ), emptyMap()))
        store.acknowledge("session", settledUserMessageIds(store["session"], previousFrame))
        assertEquals(listOf("new-user"), store["session"].map { it.id })
    }

    @Test
    fun rewindingOneSessionClearsOnlyItsPendingUsers() {
        val store = PendingUserMessageStore()
        store.sending("session", pending("rewound"))
        store.sending("other", pending("keep"))
        store.clear("session")
        assertTrue(store["session"].isEmpty())
        assertEquals(listOf("keep"), store["other"].map { it.id })
    }

    @Test
    fun emptyPending_isNoOp() {
        assertTrue(
            resolvePendingUserMessages(emptyList(), setOf("m1"), setOf("m2"), setOf("m3")).isEmpty()
        )
    }
}
