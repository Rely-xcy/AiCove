package com.aicove.feature.agent.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicove.feature.agent.data.local.database.AgentDatabase
import com.aicove.feature.agent.data.local.entity.AgentMessageEntity
import com.aicove.feature.agent.data.local.entity.MemoryRecordEntity
import com.aicove.feature.agent.data.local.entity.MemorySourceHash
import com.aicove.feature.agent.data.local.entity.MemorySourceInput
import com.aicove.feature.agent.data.local.entity.MemorySourceStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryRecordDaoTest {
    private lateinit var database: AgentDatabase
    private lateinit var messages: AgentMessageDao
    private lateinit var memories: MemoryRecordDao

    companion object {
        private const val SESSION_ID = "memory-source-session"

        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）", !androidContainer)
        }
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AgentDatabase::class.java
        ).addCallback(AgentDatabase.memorySourceCallback()).build()
        messages = database.agentMessageDao()
        memories = database.memoryRecordDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun versions_keep_independent_bodies_and_multiple_sources_and_restore_without_resurrecting_deleted_ids() = runBlocking {
        val firstMessage = message("message-1", "original evidence")
        val secondMessage = message("message-2", "second evidence")
        messages.insertAll(listOf(firstMessage, secondMessage))

        val record = record()
        val firstVersionId = memories.saveVersion(
            record = record,
            content = "independent memory body v1",
            sources = listOf(
                source(firstMessage),
                source(secondMessage, evidenceType = "user_quote", position = 1)
            ),
            reason = "initial",
            createdAt = 100L
        )
        memories.saveVersion(
            record = record.copy(description = "updated summary"),
            content = "independent memory body v2",
            sources = listOf(source(firstMessage, position = 2)),
            reason = "curated",
            createdAt = 200L
        )

        assertEquals(2, memories.getVersionHistory(record.id).size)
        assertEquals(2, database.memorySourceDao().getSourcesForVersion(firstVersionId).size)
        assertEquals(1, memories.getCurrentSources(record.id).size)

        messages.updateMessageContent(firstMessage.id, "edited evidence")
        messages.deleteMessageById(secondMessage.id)

        val history = memories.getVersionHistory(record.id)
        val firstVersion = history.single { it.id == firstVersionId }
        assertEquals("independent memory body v1", firstVersion.content)
        assertTrue(firstVersion.content != firstMessage.content)

        val firstVersionSources = database.memorySourceDao().getSourcesForVersion(firstVersionId)
        val changedLink = firstVersionSources.first { it.messageHash == MemorySourceHash.sha256(firstMessage.content) }
        assertEquals(MemorySourceStatus.SOURCE_CHANGED, changedLink.status)
        val deletedLink = firstVersionSources.first { it.messageHash == MemorySourceHash.sha256(secondMessage.content) }
        assertNull(deletedLink.messageId)
        assertEquals(MemorySourceStatus.SOURCE_DELETED, deletedLink.status)
        assertTrue(database.memorySourceDao().getSources(record.id).none { it.messageId == secondMessage.id })

        val restored = memories.restoreVersion(record.id, firstVersionId, restoredAt = 300L)
        assertNotNull(restored)
        assertEquals("independent memory body v1", restored?.content)
        assertEquals("initial", firstVersion.reason)
        assertEquals(3, memories.getVersionHistory(record.id).size)

        val restoredSources = memories.getCurrentSources(record.id)
        assertEquals(2, restoredSources.size)
        assertTrue(restoredSources.none { it.messageId == secondMessage.id })
        assertEquals(
            MemorySourceStatus.SOURCE_CHANGED,
            restoredSources.single { it.messageHash == MemorySourceHash.sha256(firstMessage.content) }.status
        )
        assertEquals(
            MemorySourceStatus.SOURCE_DELETED,
            restoredSources.single { it.messageHash == MemorySourceHash.sha256(secondMessage.content) }.status
        )
        val restoredRecord = memories.getRecordById(record.id)
        assertNotNull(restoredRecord)
        assertEquals(record.description, restoredRecord?.description)
        assertEquals(record.markdownPath, restoredRecord?.markdownPath)
    }

    @Test
    fun deleting_session_keeps_memory_and_marks_its_source_deleted() = runBlocking {
        val row = message("session-delete-message", "session evidence")
        messages.insert(row)
        val memory = record("session-delete-memory")
        memories.saveVersion(
            record = memory,
            content = "independent memory",
            sources = listOf(source(row)),
            reason = "extract",
            createdAt = 100L
        )

        messages.deleteBySession(SESSION_ID)

        val retainedRecord = memories.getRecordById(memory.id)
        assertNotNull(retainedRecord)
        assertEquals("independent memory", memories.getVersionHistory(memory.id).single().content)
        val retainedSource = memories.getCurrentSources(memory.id).single()
        assertNull(retainedSource.messageId)
        assertEquals(MemorySourceStatus.SOURCE_DELETED, retainedSource.status)
    }

    @Test
    fun changed_message_source_is_not_reported_as_current() = runBlocking {
        val row = message("changed-message", "before")
        messages.insert(row)
        val memory = record("changed-memory")
        memories.saveVersion(
            record = memory,
            content = "remembered fact",
            sources = listOf(source(row)),
            reason = "extract",
            createdAt = 100L
        )

        messages.updateMessageContent(row.id, "before")
        assertEquals(MemorySourceStatus.SOURCE_CURRENT, memories.getCurrentSources(memory.id).single().status)

        messages.updateMessageContent(row.id, "after")

        val source = memories.getCurrentSources(memory.id).single()
        assertEquals(row.id, source.messageId)
        assertEquals(MemorySourceStatus.SOURCE_CHANGED, source.status)
        assertEquals(MemorySourceHash.sha256("before"), source.messageHash)
    }

    private fun record(id: String = "memory-1") = MemoryRecordEntity(
        id = id,
        markdownPath = "/workspace/.aicode/memory/fact.md",
        name = "fact",
        description = "stable fact",
        scope = "PROJECT",
        kind = "PROFILE",
        source = "auto-distill",
        createdAt = 10L,
        updatedAt = 10L,
        hitCount = 0,
        lastHitAt = 0L,
        pinned = false
    )

    private fun message(id: String, content: String) = AgentMessageEntity(
        id = id,
        sessionId = SESSION_ID,
        role = "USER",
        content = content,
        timestamp = 100L
    )

    private fun source(
        message: AgentMessageEntity,
        evidenceType: String = "transcript",
        position: Int? = null
    ) = MemorySourceInput(
        messageId = message.id,
        evidenceType = evidenceType,
        position = position,
        messageHash = MemorySourceHash.sha256(message.content)
    )
}
