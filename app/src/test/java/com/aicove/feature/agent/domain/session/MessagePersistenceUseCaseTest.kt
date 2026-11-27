package com.aicove.feature.agent.domain.session

import com.aicove.feature.agent.data.local.dao.AgentMessageDao
import com.aicove.feature.agent.data.local.entity.AgentMessageEntity
import com.aicove.feature.agent.domain.model.AgentMessage
import com.aicove.feature.agent.domain.tool.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证落库前的内容净化与按字节限长：剥离内嵌 base64 图片 data URL、超长内容按 UTF-8 字节截断，
 * 防止超大字段撑爆单行触发 SQLite CursorWindow 崩溃。
 */
class MessagePersistenceUseCaseTest {

    /** CursorWindow 单窗口大小（AOSP 默认 2MB）；单行各文本字段上限之和必须整体留在它之下。 */
    private val CURSOR_WINDOW_BYTES = 2 * 1024 * 1024

    @Test
    fun historyPlacesSummaryBeforeRetainedMessagesWithoutChangingDisplayTimestamps() {
        val retained = AgentMessageEntity(id = "retained", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        val marker = AgentMessageEntity(id = "marker", sessionId = "session", role = "USER", content = "marker", timestamp = 200, isCompactionMarker = true)
        val summary = AgentMessageEntity(id = "summary", sessionId = "session", role = "ASSISTANT", content = "summary", timestamp = 201, isContextSummary = true)
        val latest = retained.copy(id = "latest", timestamp = 300)
        val display = listOf(retained, marker, summary, latest)
        val history = MessagePersistenceUseCase.orderHistoryEntities(display)
        assertEquals(listOf("marker", "summary", "retained", "latest"), history.map { it.id })
        assertEquals(listOf(200L, 201L, 100L, 300L), history.map { it.timestamp })
        assertEquals(listOf("retained", "marker", "summary", "latest"), display.map { it.id })
    }

    @Test
    fun recoveryExcludesOnlyItsUserRowAndLeavesNormalHistoryCached() = runBlocking {
        val session = "session"
        val pending = "__pending__"
        val older = AgentMessageEntity(id = "older", sessionId = session, role = "USER", content = "check again", timestamp = 100)
        val recovered = older.copy(id = "recovered", timestamp = 200)
        val call = ToolCall(id = "call-1", name = "readFile", arguments = mapOf("path" to JsonPrimitive("notes.txt")))
        val assistant = AgentMessageEntity(
            id = "assistant", sessionId = session, role = "ASSISTANT", content = "", timestamp = 300,
            toolCallsJson = Json.encodeToString(listOf(call))
        )
        val tool = AgentMessageEntity(
            id = "tool_${call.id}", sessionId = session, role = "TOOL", content = "file contents", timestamp = 400,
            toolCallId = call.id, toolName = call.name
        )
        val dao = mockk<AgentMessageDao>()
        coEvery { dao.getMessagesBySessionOnce(session) } returns listOf(older, recovered, assistant, tool)
        val persistence = MessagePersistenceUseCase(dao, mockk(relaxed = true))

        val previousUser = AgentMessage.UserMessage(id = older.id, content = older.content)
        val toolCall = AgentMessage.AssistantMessage(id = assistant.id, content = "", toolCalls = listOf(call))
        val toolResult = AgentMessage.ToolResultMessage(id = call.id, toolName = call.name, result = tool.content)
        val complete = listOf(previousUser, previousUser.copy(id = recovered.id), toolCall, toolResult)
        assertEquals(complete, persistence.buildHistory(session, pending))
        assertEquals(complete, persistence.buildHistory(session, pending))
        coVerify(exactly = 1) { dao.getMessagesBySessionOnce(session) }

        val history = persistence.buildHistoryExcludingMessage(session, pending, recovered.id)
        assertEquals(listOf(previousUser, toolCall, toolResult), history)

        coEvery { dao.getMessagesBySessionOnce(session) } throws AssertionError("Recovery evicted the normal history cache")
        assertEquals(complete, persistence.buildHistory(session, pending))
    }

    @Test
    fun excludingOnlyTailAfterSummaryDoesNotReplayFoldedRows() = runBlocking {
        val session = "session"
        val pending = "__pending__"
        val old = AgentMessageEntity(id = "old", sessionId = session, role = "USER", content = "already summarized", timestamp = 100)
        val marker = old.copy(id = "marker", content = "marker", timestamp = 110, isCompactionMarker = true)
        val summary = AgentMessageEntity(
            id = "summary", sessionId = session, role = "ASSISTANT", content = "summary", timestamp = 111,
            isContextSummary = true, foldsUpToTimestamp = old.timestamp
        )
        val recovered = old.copy(id = "recovered", content = "resume", timestamp = 200)
        val dao = mockk<AgentMessageDao>()
        coEvery { dao.getMessagesBySessionOnce(session) } returns listOf(old, marker, summary, recovered)
        val persistence = MessagePersistenceUseCase(dao, mockk(relaxed = true))

        val complete = persistence.buildHistory(session, pending)
        assertEquals(listOf(marker.id, summary.id, recovered.id), complete.map { it.id })
        val expected = complete.filterNot { it.id == recovered.id }
        val history = persistence.buildHistoryExcludingMessage(session, pending, recovered.id)
        assertEquals(expected, history)
    }

    @Test
    fun sanitizeContent_stripsInlineBase64Image() {
        val input = "这是回复。看图：![截图](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==) 结束。"

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("图片已省略"))
        assertFalse(result.contains("iVBORw0KGgo"))
        assertTrue(result.contains("这是回复"))
    }

    @Test
    fun sanitizeContent_stripsBareDataUrl() {
        val input = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ=="

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("图片已省略"))
        assertFalse(result.contains("/9j/4AAQSk"))
    }

    @Test
    fun sanitizeContent_truncatesOversizedPlainText() {
        val oversized = "a".repeat(300_000)

        val result = MessagePersistenceUseCase.sanitizeContent(oversized)

        assertTrue(result.endsWith(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER))
        val kept = result.removeSuffix(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER)
        assertTrue(kept.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
    }

    @Test
    fun sanitizeContent_boundsBytesForMultibyteText() {
        // 中文单字符 3 字节：字符数上限约束不住真实占用，必须按字节截断。
        val oversized = "汉".repeat(100_000)

        val result = MessagePersistenceUseCase.sanitizeContent(oversized)

        assertTrue(result.endsWith(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER))
        val kept = result.removeSuffix(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER)
        assertTrue(kept.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
    }

    @Test
    fun sanitizeContent_keepsNormalTextUnchanged() {
        val normal = "普通消息，不需要处理。".repeat(10)

        assertEquals(normal, MessagePersistenceUseCase.sanitizeContent(normal))
    }

    @Test
    fun rowFieldCapsStayUnderCursorWindowBudget() {
        // 单行最坏占用 = 正文 + 思考（各 MAX_CONTENT_BYTES）+ 工具调用快照/思考块/思考签名/模式提醒
        // （各 MAX_SNAPSHOT_BYTES）+ 工具入参 MAX_TOOL_ARGS_BYTES + 附件 MAX_ATTACHMENTS_BYTES。
        // 任一上限被调大都会撞上下面的断言：那时必须回头核对 MessagePersistenceUseCase 的 KDoc，
        // 确认这一行仍放得进 CursorWindow（单窗口约 2MB，窗口会按行预填充，一行超限即读库崩溃）。
        val worstCaseRowBytes = MessagePersistenceUseCase.MAX_CONTENT_BYTES * 2 +
            MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES * 4 +
            MessagePersistenceUseCase.MAX_TOOL_ARGS_BYTES +
            MessagePersistenceUseCase.MAX_ATTACHMENTS_BYTES

        assertEquals(870_000, worstCaseRowBytes)
        assertTrue(
            "单行字段上限之和 $worstCaseRowBytes 逼近 CursorWindow 单窗口 $CURSOR_WINDOW_BYTES",
            worstCaseRowBytes <= CURSOR_WINDOW_BYTES / 2
        )
    }

    @Test
    fun capBytes_truncatesOversizedJson() {
        val oversized = "[{\"id\":\"" + "a".repeat(300_000) + "\"}]"

        val result = MessagePersistenceUseCase.capBytes(
            oversized,
            MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES
        )

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
    }

    @Test
    fun capBytes_doesNotSplitSurrogatePair() {
        val oversized = "😀".repeat(50_000)

        val result = MessagePersistenceUseCase.capBytes(
            oversized,
            MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES
        )

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
        assertEquals(0, result.length % 2)
    }

    @Test
    fun capBytes_keepsNormalJsonUnchanged() {
        val normal = "[{\"id\":\"call_1\",\"name\":\"writeFile\"}]"

        assertEquals(
            normal,
            MessagePersistenceUseCase.capBytes(normal, MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
        )
    }
}
