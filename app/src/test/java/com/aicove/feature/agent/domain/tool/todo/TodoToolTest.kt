package com.aicove.feature.agent.domain.tool.todo

import com.aicove.feature.agent.data.local.dao.TodoItemDao
import com.aicove.feature.agent.data.local.entity.TodoItemEntity
import com.aicove.feature.agent.domain.model.AgentContext
import com.aicove.feature.agent.domain.model.TodoStatus
import com.aicove.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `todo` 工具的增量语义：按 subject 定位、只改一项，整表替换只作兜底。
 *
 * 这一层是「AI 不记得更新待办」的第一道修复——每完成一步都要重发整张清单的话，
 * 模型会本能回避更新；增量接口把一次更新压到一次廉价调用。
 */
class TodoToolTest {

    private val dao = FakeTodoItemDao()
    private val tool = TodoTool(dao)
    private val context = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/ws",
        language = null,
        sessionId = SESSION
    )

    // ------------------------------------------------------------ add

    @Test
    fun add_newItem_defaultsToPending() = runTest {
        val result = tool.executeWithContext(
            args("action" to str("add"), "subject" to str("分析现有实现")),
            context
        )

        assertEquals(1, dao.rows.size)
        assertEquals(TodoStatus.PENDING.name, dao.rows.single().status)
        assertEquals("pending", statusOf(result, "分析现有实现"))
        assertTrue(messageOf(result).contains("已新增"))
        assertTrue(messageOf(result).contains("下一个未完成项"))
    }

    @Test
    fun add_existingSubject_updatesInsteadOfDuplicating() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(
            args(
                "action" to str("add"),
                "subject" to str("分析现有实现"),
                "status" to str("in_progress")
            ),
            context
        )

        assertEquals(1, dao.rows.size)
        assertEquals(TodoStatus.IN_PROGRESS.name, dao.rows.single().status)
        assertTrue(messageOf(result).contains("按更新处理"))
    }

    @Test
    fun add_beyondCap_returnsError() = runTest {
        repeat(50) { index -> seed("待办 $index", order = index, id = "id-$index") }

        val result = tool.executeWithContext(
            args("action" to str("add"), "subject" to str("第 51 项")),
            context
        )

        assertEquals("TOO_MANY_ITEMS", errorCode(result))
        assertEquals(50, dao.rows.size)
    }

    @Test
    fun add_itemsBeyondCapRollsBackEarlierUpdatesInSameRequest() = runTest {
        repeat(50) { index ->
            seed("待办 $index", order = index, id = "id-$index")
        }
        val original = dao.rows.toList()

        val result = tool.executeWithContext(
            args(
                "action" to str("add"),
                "items" to items(
                    mapOf("subject" to str("待办 0"), "status" to str("completed")),
                    mapOf("subject" to str("第 51 项"))
                )
            ),
            context
        )

        assertEquals("TOO_MANY_ITEMS", errorCode(result))
        assertEquals(original, dao.rows)
    }

    @Test
    fun add_topLevelParentIdAcceptsSubjectAndStableId() = runTest {
        val parent = seed("父任务")

        val bySubject = tool.executeWithContext(
            args(
                "action" to str("add"),
                "subject" to str("按标题挂接的子任务"),
                "parent_id" to str(parent.subject)
            ),
            context
        )
        val byFuzzySubject = tool.executeWithContext(
            args(
                "action" to str("add"),
                "subject" to str("按模糊标题挂接的子任务"),
                "parent_id" to str("父")
            ),
            context
        )
        val byId = tool.executeWithContext(
            args(
                "action" to str("add"),
                "subject" to str("按ID挂接的子任务"),
                "parent_id" to str(parent.id)
            ),
            context
        )

        assertNull(errorCodeOrNull(bySubject))
        assertNull(errorCodeOrNull(byFuzzySubject))
        assertNull(errorCodeOrNull(byId))
        assertEquals(parent.id, dao.rows.single { it.subject == "按标题挂接的子任务" }.parentId)
        assertEquals(parent.id, dao.rows.single { it.subject == "按模糊标题挂接的子任务" }.parentId)
        assertEquals(parent.id, dao.rows.single { it.subject == "按ID挂接的子任务" }.parentId)

        val completed = tool.executeWithContext(
            args("action" to str("update"), "subject" to str(parent.subject), "status" to str("completed")),
            context
        )
        assertEquals("PARENT_HAS_UNFINISHED_CHILDREN", errorCode(completed))
        assertEquals(TodoStatus.PENDING.name, dao.rows.single { it.id == parent.id }.status)
    }

    @Test
    fun add_detectsCycleThroughLegacyParentIdWithDifferentCaseWithoutWriting() = runTest {
        val first = seed("环A", id = "id-a")
        val second = seed("环B", order = 1, id = "id-b")
        dao.rows[0] = first.copy(parentId = "ID-B")
        val original = dao.rows.toList()

        val result = tool.executeWithContext(
            args(
                "action" to str("add"),
                "subject" to str(second.subject),
                "parent_id" to str(first.id)
            ),
            context
        )

        assertEquals("INVALID_PARENT_HIERARCHY", errorCode(result))
        assertEquals(original, dao.rows)
    }

    @Test
    fun add_itemsCanReferenceLaterParentAndBlocksCompletionUntilChildCompletes() = runTest {
        val added = tool.executeWithContext(
            args(
                "action" to str("add"),
                "items" to items(
                    mapOf("subject" to str("子任务"), "parent_id" to str("父任务")),
                    mapOf("subject" to str("父任务"))
                )
            ),
            context
        )

        assertNull(errorCodeOrNull(added))
        val parent = dao.rows.single { it.subject == "父任务" }
        val child = dao.rows.single { it.subject == "子任务" }
        assertEquals(parent.id, child.parentId)

        val blocked = tool.executeWithContext(
            args("action" to str("update"), "subject" to str("父任务"), "status" to str("completed")),
            context
        )
        assertEquals("PARENT_HAS_UNFINISHED_CHILDREN", errorCode(blocked))

        tool.executeWithContext(
            args("action" to str("update"), "subject" to str("子任务"), "status" to str("completed")),
            context
        )
        val completed = tool.executeWithContext(
            args("action" to str("update"), "subject" to str("父任务"), "status" to str("completed")),
            context
        )
        assertNull(errorCodeOrNull(completed))
        assertEquals(TodoStatus.COMPLETED.name, dao.rows.single { it.id == parent.id }.status)
    }

    @Test
    fun add_rejectsCompletedParentWithUnfinishedChildWithoutWriting() = runTest {
        val original = seed("保留原任务")

        val result = tool.executeWithContext(
            args(
                "action" to str("add"),
                "items" to items(
                    mapOf("subject" to str("已完成父任务"), "status" to str("completed")),
                    mapOf("subject" to str("未完成子任务"), "parent_id" to str("已完成父任务"))
                )
            ),
            context
        )

        assertEquals("PARENT_HAS_UNFINISHED_CHILDREN", errorCode(result))
        assertEquals(listOf(original), dao.rows)
    }

    // ------------------------------------------------------------ update

    @Test
    fun exactDuplicateSubjectsMakeUpdateAndRemoveAmbiguousWithoutWriting() = runTest {
        seed("旧任务")
        val replaced = tool.executeWithContext(
            args(
                "action" to str("replace"),
                "items" to items(
                    mapOf("subject" to str("重复标题")),
                    mapOf("subject" to str("重复标题"))
                )
            ),
            context
        )
        assertNull(errorCodeOrNull(replaced))
        val original = dao.rows.toList()

        val update = tool.executeWithContext(
            args("action" to str("update"), "subject" to str("重复标题"), "status" to str("completed")),
            context
        )
        val remove = tool.executeWithContext(
            args("action" to str("remove"), "subject" to str("重复标题")),
            context
        )

        assertEquals("AMBIGUOUS_SUBJECT", errorCode(update))
        assertEquals("AMBIGUOUS_SUBJECT", errorCode(remove))
        assertEquals(original, dao.rows)
    }

    @Test
    fun update_parentCompletionChecksUnfinishedGrandchildrenInLegacyTree() = runTest {
        val parent = seed("根任务")
        val child = seed("已完成子任务", status = TodoStatus.COMPLETED, order = 1, id = "id-child")
            .copy(parentId = parent.id)
        val grandchild = seed("未完成孙任务", order = 2, id = "id-grandchild")
            .copy(parentId = child.id)
        dao.rows[1] = child
        dao.rows[2] = grandchild

        val result = tool.executeWithContext(
            args("action" to str("update"), "subject" to str(parent.subject), "status" to str("completed")),
            context
        )

        assertEquals("PARENT_HAS_UNFINISHED_CHILDREN", errorCode(result))
        assertEquals(TodoStatus.PENDING.name, dao.rows.first { it.id == parent.id }.status)
    }

    @Test
    fun update_reopeningChildUnderCompletedParentIsRejectedWithoutWriting() = runTest {
        val parent = seed("已完成父任务", status = TodoStatus.COMPLETED)
        val child = seed("子任务", order = 1, id = "id-child").copy(parentId = parent.id)
        dao.rows[1] = child
        val original = dao.rows.toList()

        val result = tool.executeWithContext(
            args("action" to str("update"), "subject" to str(child.subject), "status" to str("pending")),
            context
        )

        assertEquals("COMPLETED_PARENT_REOPEN", errorCode(result))
        assertEquals(original, dao.rows)
    }

    @Test
    fun update_fuzzySubject_marksCompletedAndKeepsId() = runTest {
        val seeded = seed("重做任务待办引擎")

        val result = tool.executeWithContext(
            args(
                "action" to str("update"),
                "subject" to str("待办引擎"),
                "status" to str("completed")
            ),
            context
        )

        assertEquals("completed", statusOf(result, "重做任务待办引擎"))
        assertEquals(seeded.id, dao.rows.single().id)
        assertTrue(messageOf(result).contains("已完成"))
    }

    @Test
    fun update_ambiguousSubject_returnsErrorWithoutWriting() = runTest {
        seed("重做待办引擎")
        seed("待办引擎的注入", order = 1, id = "id-second")

        val result = tool.executeWithContext(
            args(
                "action" to str("update"),
                "subject" to str("待办引擎"),
                "status" to str("completed")
            ),
            context
        )

        assertEquals("AMBIGUOUS_SUBJECT", errorCode(result))
        assertTrue(dao.rows.all { it.status == TodoStatus.PENDING.name })
    }

    @Test
    fun update_unknownSubject_returnsErrorWithCurrentList() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(
            args("action" to str("update"), "subject" to str("不存在的项"), "status" to str("completed")),
            context
        )

        assertEquals("SUBJECT_NOT_FOUND", errorCode(result))
        assertTrue((result as ToolResult.Error).message.contains("分析现有实现"))
    }

    @Test
    fun update_withoutAnyField_returnsError() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(
            args("action" to str("update"), "subject" to str("分析现有实现")),
            context
        )

        assertEquals("MISSING_FIELDS", errorCode(result))
    }

    @Test
    fun update_invalidStatus_returnsError() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(
            args("action" to str("update"), "subject" to str("分析现有实现"), "status" to str("halfway")),
            context
        )

        assertEquals("INVALID_STATUS", errorCode(result))
    }

    // ------------------------------------------------------------ remove / replace / list

    @Test
    fun remove_parentWithChildrenIsRejectedWithoutWriting() = runTest {
        val parent = seed("父任务")
        val child = seed("子任务", order = 1, id = "id-child").copy(parentId = parent.id)
        dao.rows[1] = child
        val original = dao.rows.toList()

        val result = tool.executeWithContext(
            args("action" to str("remove"), "subject" to str(parent.subject)),
            context
        )

        assertEquals("PARENT_HAS_CHILDREN", errorCode(result))
        assertEquals(original, dao.rows)
    }

    @Test
    fun remove_deletesItem() = runTest {
        seed("分析现有实现")
        seed("重写工具", order = 1, id = "id-second")

        val result = tool.executeWithContext(
            args("action" to str("remove"), "subject" to str("分析现有实现")),
            context
        )

        assertEquals(listOf("重写工具"), dao.rows.map { it.subject })
        assertTrue(messageOf(result).contains("已删除"))
    }

    @Test
    fun replace_keepsIdAndCreatedAtBySubject() = runTest {
        val seeded = seed("分析现有实现")
        val createdAt = seeded.createdAt

        val result = tool.executeWithContext(
            args(
                "action" to str("replace"),
                "items" to items(
                    mapOf("subject" to str("分析现有实现"), "status" to str("completed")),
                    mapOf("subject" to str("重写工具"))
                )
            ),
            context
        )

        assertEquals(2, dao.rows.size)
        val kept = dao.rows.first { it.subject == "分析现有实现" }
        assertEquals(seeded.id, kept.id)
        assertEquals(createdAt, kept.createdAt)
        assertEquals(TodoStatus.COMPLETED.name, kept.status)
        assertEquals(1, dao.rows.count { it.subject == "重写工具" })
        assertEquals("completed", statusOf(result, "分析现有实现"))
    }

    @Test
    fun replace_itemsCanReferenceLaterParent() = runTest {
        seed("将被替换的旧任务")

        val result = tool.executeWithContext(
            args(
                "action" to str("replace"),
                "items" to items(
                    mapOf("subject" to str("替换后子任务"), "parent_id" to str("替换后父任务")),
                    mapOf("subject" to str("替换后父任务"))
                )
            ),
            context
        )

        assertNull(errorCodeOrNull(result))
        val parent = dao.rows.single { it.subject == "替换后父任务" }
        val child = dao.rows.single { it.subject == "替换后子任务" }
        assertEquals(parent.id, child.parentId)
        assertEquals(2, dao.rows.size)

        val blocked = tool.executeWithContext(
            args("action" to str("update"), "subject" to str(parent.subject), "status" to str("completed")),
            context
        )
        assertEquals("PARENT_HAS_UNFINISHED_CHILDREN", errorCode(blocked))
        tool.executeWithContext(
            args("action" to str("update"), "subject" to str(child.subject), "status" to str("completed")),
            context
        )
        val completed = tool.executeWithContext(
            args("action" to str("update"), "subject" to str(parent.subject), "status" to str("completed")),
            context
        )
        assertNull(errorCodeOrNull(completed))
    }

    @Test
    fun replaceCanResolveStableIdBeforeLaterParentAndRejectsCyclesAtomically() = runTest {
        val parent = seed("沿用ID的父任务", id = "stable-parent-id")
        val linked = tool.executeWithContext(
            args(
                "action" to str("replace"),
                "items" to items(
                    mapOf("subject" to str("子任务"), "parent_id" to str(parent.id)),
                    mapOf("subject" to str(parent.subject))
                )
            ),
            context
        )
        assertNull(errorCodeOrNull(linked))
        assertEquals(parent.id, dao.rows.single { it.subject == "子任务" }.parentId)
        val stable = dao.rows.toList()

        val cycle = tool.executeWithContext(
            args(
                "action" to str("replace"),
                "items" to items(
                    mapOf("subject" to str("环A"), "parent_id" to str("环B")),
                    mapOf("subject" to str("环B"), "parent_id" to str("环A"))
                )
            ),
            context
        )
        assertEquals("INVALID_PARENT_HIERARCHY", errorCode(cycle))
        assertEquals(stable, dao.rows)
    }

    @Test
    fun replace_rejectsCompletedParentWithUnfinishedChildWithoutWriting() = runTest {
        val original = seed("保留原任务")

        val result = tool.executeWithContext(
            args(
                "action" to str("replace"),
                "items" to items(
                    mapOf("subject" to str("已完成父任务"), "status" to str("completed")),
                    mapOf("subject" to str("未完成子任务"), "parent_id" to str("已完成父任务"))
                )
            ),
            context
        )

        assertEquals("PARENT_HAS_UNFINISHED_CHILDREN", errorCode(result))
        assertEquals(listOf(original), dao.rows)
    }

    @Test
    fun invalidParentReferencesAndAmbiguousSubjects_leaveOriginalListUnchanged() = runTest {
        val existing = seed("原有任务")
        val original = dao.rows.toList()

        val orphan = tool.executeWithContext(
            args(
                "action" to str("add"),
                "items" to items(
                    mapOf("subject" to str("本可新增")),
                    mapOf("subject" to str("孤儿"), "parent_id" to str("不存在的父任务"))
                )
            ),
            context
        )
        assertEquals("PARENT_NOT_FOUND", errorCode(orphan))
        assertEquals(original, dao.rows)

        seed("模糊父任务甲", order = 1, id = "parent-1")
        seed("模糊父任务乙", order = 2, id = "parent-2")
        val beforeAmbiguousAdd = dao.rows.toList()
        val ambiguousAdd = tool.executeWithContext(
            args(
                "action" to str("add"),
                "subject" to str("引用模糊父项的子任务"),
                "parent_id" to str("模糊父任务")
            ),
            context
        )
        assertEquals("AMBIGUOUS_PARENT", errorCode(ambiguousAdd))
        assertEquals(beforeAmbiguousAdd, dao.rows)

        val beforeAmbiguousReplace = dao.rows.toList()
        val ambiguousReplace = tool.executeWithContext(
            args(
                "action" to str("replace"),
                "items" to items(
                    mapOf("subject" to str("重复规划父项")),
                    mapOf("subject" to str("重复规划父项")),
                    mapOf("subject" to str("规划子项"), "parent_id" to str("重复规划父项"))
                )
            ),
            context
        )
        assertEquals("AMBIGUOUS_PARENT", errorCode(ambiguousReplace))
        assertEquals(beforeAmbiguousReplace, dao.rows)
        assertTrue(dao.rows.any { it.id == existing.id })
    }

    @Test
    fun selfParentAndCycles_areRejectedWithoutWriting() = runTest {
        seed("原有任务")
        val original = dao.rows.toList()

        val selfParent = tool.executeWithContext(
            args(
                "action" to str("add"),
                "items" to items(mapOf("subject" to str("自父项"), "parent_id" to str("自父项")))
            ),
            context
        )
        assertEquals("INVALID_PARENT_HIERARCHY", errorCode(selfParent))
        assertEquals(original, dao.rows)

        val cycle = tool.executeWithContext(
            args(
                "action" to str("add"),
                "items" to items(
                    mapOf("subject" to str("环A"), "parent_id" to str("环B")),
                    mapOf("subject" to str("环B"), "parent_id" to str("环A"))
                )
            ),
            context
        )
        assertEquals("INVALID_PARENT_HIERARCHY", errorCode(cycle))
        assertEquals(original, dao.rows)
    }

    @Test
    fun list_returnsCurrentItemsWithoutWriting() = runTest {
        seed("分析现有实现")

        val result = tool.executeWithContext(args("action" to str("list")), context)

        assertEquals("分析现有实现", subjectOfFirstItem(result))
        assertEquals(1, dao.rows.size)
    }

    // ------------------------------------------------------------ action 推断

    @Test
    fun inferAction_fromParameters() = runTest {
        seed("分析现有实现")

        // 只给 items：整表替换
        tool.executeWithContext(args("items" to items(mapOf("subject" to str("只留这一项")))), context)
        assertEquals(listOf("只留这一项"), dao.rows.map { it.subject })

        // 给 status：改某项
        tool.executeWithContext(
            args("subject" to str("只留这一项"), "status" to str("completed")),
            context
        )
        assertEquals(TodoStatus.COMPLETED.name, dao.rows.single().status)

        // 只给 subject：加一项
        tool.executeWithContext(args("subject" to str("再加一项")), context)
        assertEquals(2, dao.rows.size)

        // 什么都不给：只读
        val result = tool.executeWithContext(emptyMap(), context)
        assertEquals(2, dao.rows.size)
        assertNull(errorCodeOrNull(result))
    }

    // ------------------------------------------------------------ 辅助

    private fun seed(
        subject: String,
        status: TodoStatus = TodoStatus.PENDING,
        order: Int = 0,
        id: String = "id-$subject"
    ): TodoItemEntity {
        val now = System.currentTimeMillis()
        val entity = TodoItemEntity(
            id = id,
            sessionId = SESSION,
            subject = subject,
            status = status.name,
            order = order,
            createdAt = now,
            updatedAt = now
        )
        dao.rows.add(entity)
        return entity
    }

    private fun args(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = mapOf(*pairs)

    private fun str(value: String): JsonElement = JsonPrimitive(value)

    private fun items(vararg entries: Map<String, JsonElement>): JsonElement =
        kotlinx.serialization.json.JsonArray(entries.map { JsonObject(it) })

    private fun dataOf(result: ToolResult): JsonObject {
        assertTrue("期望成功结果，实际：$result", result is ToolResult.Success)
        return (result as ToolResult.Success).data.jsonObject
    }

    private fun messageOf(result: ToolResult): String =
        dataOf(result)["message"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun subjectsOf(result: ToolResult): List<String> = dataOf(result)["items"]!!
        .jsonArray
        .map { it.jsonObject["subject"]?.jsonPrimitive?.contentOrNull.orEmpty() }

    private fun subjectOfFirstItem(result: ToolResult): String? = subjectsOf(result).firstOrNull()

    private fun statusOf(result: ToolResult, subject: String): String? = dataOf(result)["items"]!!
        .jsonArray
        .map { it.jsonObject }
        .firstOrNull { it["subject"]?.jsonPrimitive?.contentOrNull == subject }
        ?.get("status")
        ?.jsonPrimitive
        ?.contentOrNull

    private fun errorCode(result: ToolResult): String {
        assertTrue("期望失败结果，实际：$result", result is ToolResult.Error)
        return (result as ToolResult.Error).code
    }

    private fun errorCodeOrNull(result: ToolResult): String? = (result as? ToolResult.Error)?.code

    private companion object {
        const val SESSION = "session-1"
    }
}

/**
 * 最小内存实现：[TodoItemDao] 是 Room 接口，单测里不需要真库。
 * 测试直接读写 [rows]，断言的就是这份内存状态。
 */
private class FakeTodoItemDao : TodoItemDao {
    val rows = mutableListOf<TodoItemEntity>()

    override suspend fun upsert(item: TodoItemEntity) {
        rows.removeAll { it.id == item.id }
        rows.add(item)
    }

    override suspend fun upsertAll(items: List<TodoItemEntity>) {
        items.forEach { upsert(it) }
    }

    override fun getBySession(sessionId: String): Flow<List<TodoItemEntity>> =
        MutableStateFlow(orderedBySession(sessionId))

    override suspend fun getBySessionOnce(sessionId: String): List<TodoItemEntity> = orderedBySession(sessionId)

    override suspend fun getAllOnce(): List<TodoItemEntity> = rows.toList()

    override suspend fun getPageAfter(lastCreatedAt: Long, lastId: String, limit: Int): List<TodoItemEntity> =
        rows.filter { it.createdAt > lastCreatedAt || (it.createdAt == lastCreatedAt && it.id > lastId) }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .take(limit)

    override suspend fun getBySessionPageAfter(
        sessionId: String,
        lastCreatedAt: Long,
        lastId: String,
        limit: Int
    ): List<TodoItemEntity> = getPageAfter(lastCreatedAt, lastId, limit).filter { it.sessionId == sessionId }

    override suspend fun delete(id: String) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteBySession(sessionId: String) {
        rows.removeAll { it.sessionId == sessionId }
    }

    override suspend fun getMaxOrder(sessionId: String): Int? =
        rows.filter { it.sessionId == sessionId }.maxOfOrNull { it.order }

    private fun orderedBySession(sessionId: String): List<TodoItemEntity> = rows
        .filter { it.sessionId == sessionId }
        .sortedWith(compareBy({ it.order }, { -it.priority }))
}
