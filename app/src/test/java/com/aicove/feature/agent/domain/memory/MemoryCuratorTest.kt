package com.aicove.feature.agent.domain.memory

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryCuratorTest {

    private fun memory(name: String, description: String = name, content: String = "$name body") = Memory(
        name = name,
        description = description,
        scope = MemoryScope.PROJECT,
        content = content
    )

    private fun duplicatePair(): List<Memory> {
        val description = "alpine glacier ridge summit trail snow ice climb mountaineering crampon rope belay ascent descent valley altitude"
        return listOf(
            memory("alpine", description, "retained body"),
            memory("glacier", description, "source body")
        )
    }

    private fun repository(memories: List<Memory>, saveResult: Boolean = true): MemoryRepository {
        val repository = mockk<MemoryRepository>()
        every { repository.listMemories("/ws") } returns memories
        every {
            repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saveResult
        every { repository.deleteMemory(any(), any(), any()) } returns true
        return repository
    }

    @Test
    fun `合并保存成功后才归档来源且每条只退场一次`() = runTest {
        val pair = duplicatePair()
        val untouched = listOf(memory("orchid"), memory("cobalt"))
        val repository = repository(pair + untouched)
        var saved = false
        every {
            repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            saved = true
            true
        }
        every { repository.deleteMemory("glacier", MemoryScope.PROJECT, "/ws") } answers {
            assertTrue(saved)
            true
        }

        val result = MemoryCurator(repository).curate("/ws")

        assertEquals(1, result.merged)
        assertEquals(1, result.archived)
        assertTrue(result.changed)
        assertEquals(untouched, result.remaining)
        verify(exactly = 1) {
            repository.saveMemory(
                "alpine", pair.first().description, "retained body\n\nsource body",
                MemoryScope.PROJECT, "/ws", MemoryKind.NOTE, "", 0L, null
            )
        }
        verify(exactly = 1) { repository.deleteMemory("glacier", MemoryScope.PROJECT, "/ws") }
        verify(exactly = 1) { repository.deleteMemory(any(), any(), any()) }
    }

    @Test
    fun `合并保存失败不归档来源且整组保留在 remaining`() = runTest {
        val memories = duplicatePair() + listOf(memory("orchid"), memory("cobalt"))
        val repository = repository(memories, saveResult = false)

        val result = MemoryCurator(repository).curate("/ws")

        assertEquals(0, result.merged)
        assertEquals(0, result.archived)
        assertFalse(result.changed)
        assertEquals(memories, result.remaining)
        verify(exactly = 1) {
            repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { repository.deleteMemory(any(), any(), any()) }
    }

    @Test
    fun `部分组保存成功时只归档成功组且保留失败组`() = runTest {
        val description = "nebula galaxy cosmos stellar planet orbit telescope asteroid comet meteor satellite lunar solar eclipse observatory astronomy"
        val failedPair = listOf(
            memory("nebula", description, "stellar light"),
            memory("galaxy", description, "stellar light")
        )
        val repository = repository(duplicatePair() + failedPair, saveResult = false)
        every {
            repository.saveMemory("alpine", any(), any(), any(), any(), any(), any(), any(), any())
        } returns true

        val result = MemoryCurator(repository).curate("/ws")

        assertEquals(1, result.merged)
        assertEquals(1, result.archived)
        assertEquals(failedPair, result.remaining)
        verify(exactly = 2) {
            repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 1) { repository.deleteMemory("glacier", MemoryScope.PROJECT, "/ws") }
        verify(exactly = 1) { repository.deleteMemory(any(), any(), any()) }
    }

    @Test
    fun `没有合并与归档时不写入并保留全部记忆`() = runTest {
        val memories = listOf("orchid", "cobalt", "harbor", "cinnamon").map { memory(it) }
        val repository = repository(memories)

        val result = MemoryCurator(repository).curate("/ws")

        assertEquals(0, result.merged)
        assertEquals(0, result.archived)
        assertFalse(result.changed)
        assertEquals(memories, result.remaining)
        verify(exactly = 0) {
            repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { repository.deleteMemory(any(), any(), any()) }
    }

    @Test
    fun `合并失败仍独立归档过期记忆`() = runTest {
        val expired = memory("expired").copy(
            createdAt = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
        )
        val alive = duplicatePair() + memory("orchid")
        val repository = repository(alive + expired, saveResult = false)

        val result = MemoryCurator(repository).curate("/ws")

        assertEquals(0, result.merged)
        assertEquals(1, result.archived)
        assertTrue(result.changed)
        assertEquals(alive, result.remaining)
        verify(exactly = 1) { repository.deleteMemory("expired", MemoryScope.PROJECT, "/ws") }
        verify(exactly = 1) { repository.deleteMemory(any(), any(), any()) }
    }

    @Test
    fun `首次读取取消时向上传播且不写入`() = runTest {
        val repository = repository(emptyList())
        val cancellation = CancellationException("cancel initial read")
        every { repository.listMemories("/ws") } throws cancellation

        try {
            MemoryCurator(repository).curate("/ws")
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        verify(exactly = 0) {
            repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { repository.deleteMemory(any(), any(), any()) }
    }

    @Test
    fun `合并重读取消时向上传播且不归档来源`() = runTest {
        val memories = duplicatePair() + listOf(memory("orchid"), memory("cobalt"))
        val repository = repository(memories)
        val cancellation = CancellationException("cancel merge read")
        var reads = 0
        every { repository.listMemories("/ws") } answers {
            reads++
            if (reads == 1) memories else throw cancellation
        }

        try {
            MemoryCurator(repository).curate("/ws")
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        verify(exactly = 0) {
            repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { repository.deleteMemory(any(), any(), any()) }
    }

    @Test
    fun `模型调用取消时向上传播且不执行待合并组`() = runTest {
        val survivors = listOf("orchid", "cobalt", "harbor", "cinnamon", "quartz", "violin")
            .map { memory(it) }
        val repository = repository(duplicatePair() + survivors)
        val cancellation = CancellationException("cancel model")

        try {
            MemoryCurator(repository).curate("/ws") { throw cancellation }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        verify(exactly = 0) {
            repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { repository.deleteMemory(any(), any(), any()) }
    }
}
