package com.aicove.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.aicove.feature.agent.data.local.entity.MemoryRecordEntity
import com.aicove.feature.agent.data.local.entity.MemorySourceEntity
import com.aicove.feature.agent.data.local.entity.MemorySourceHash
import com.aicove.feature.agent.data.local.entity.MemorySourceInput
import com.aicove.feature.agent.data.local.entity.MemorySourceStatus
import com.aicove.feature.agent.data.local.entity.MemoryVersionEntity

@Dao
interface MemoryRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecord(record: MemoryRecordEntity): Long

    @Update
    suspend fun updateRecord(record: MemoryRecordEntity): Int

    @Query("SELECT id, content FROM agent_messages WHERE id IN (:messageIds)")
    suspend fun findMessageContents(messageIds: List<String>): List<AgentMessageContent>

    @Insert
    suspend fun insertVersion(version: MemoryVersionEntity): Long

    @Insert
    suspend fun insertSources(sources: List<MemorySourceEntity>)

    @Query("SELECT * FROM memory_versions WHERE memoryId = :memoryId ORDER BY id DESC")
    suspend fun getVersionHistory(memoryId: String): List<MemoryVersionEntity>

    @Query("SELECT * FROM memory_versions WHERE memoryId = :memoryId AND id = :versionId LIMIT 1")
    suspend fun findVersion(memoryId: String, versionId: Long): MemoryVersionEntity?

    @Query("SELECT * FROM memory_sources WHERE versionId = :versionId ORDER BY id ASC")
    suspend fun findSources(versionId: Long): List<MemorySourceEntity>

    @Query(
        """
        SELECT * FROM memory_sources
        WHERE versionId = (
            SELECT id FROM memory_versions
            WHERE memoryId = :memoryId
            ORDER BY id DESC LIMIT 1
        )
        ORDER BY id ASC
        """
    )
    suspend fun getCurrentSources(memoryId: String): List<MemorySourceEntity>

    @Query("SELECT * FROM memory_records WHERE id = :memoryId LIMIT 1")
    suspend fun getRecordById(memoryId: String): MemoryRecordEntity?

    @Query("SELECT * FROM memory_records WHERE markdownPath = :markdownPath LIMIT 1")
    suspend fun getRecordByMarkdownPath(markdownPath: String): MemoryRecordEntity?

    @Transaction
    suspend fun saveVersion(
        record: MemoryRecordEntity,
        content: String,
        sources: List<MemorySourceInput>,
        reason: String,
        createdAt: Long
    ): Long {
        val currentRecord = record.copy(updatedAt = createdAt)
        if (getRecordById(record.id) == null) {
            insertRecord(currentRecord)
        } else {
            updateRecord(currentRecord)
        }

        val versionId = insertVersion(
            MemoryVersionEntity(
                memoryId = record.id,
                content = content,
                name = record.name,
                description = record.description,
                kind = record.kind,
                source = record.source,
                pinned = record.pinned,
                reason = reason,
                createdAt = createdAt
            )
        )
        val currentContents = if (sources.isEmpty()) {
            emptyMap()
        } else {
            findMessageContents(sources.map { it.messageId }.distinct()).associate { it.id to it.content }
        }
        val sourceRows = sources.map { source ->
            val currentContent = currentContents[source.messageId]
            val isDeleted = currentContent == null
            MemorySourceEntity(
                memoryId = record.id,
                versionId = versionId,
                messageId = if (isDeleted) null else source.messageId,
                evidenceType = source.evidenceType,
                position = source.position,
                messageHash = source.messageHash,
                status = when {
                    isDeleted -> MemorySourceStatus.SOURCE_DELETED
                    MemorySourceHash.sha256(currentContent!!) == source.messageHash ->
                        MemorySourceStatus.SOURCE_CURRENT
                    else -> MemorySourceStatus.SOURCE_CHANGED
                },
                createdAt = createdAt
            )
        }
        if (sourceRows.isNotEmpty()) insertSources(sourceRows)
        return versionId
    }

    @Transaction
    suspend fun restoreVersion(
        memoryId: String,
        versionId: Long,
        restoredAt: Long
    ): MemoryVersionEntity? {
        val record = getRecordById(memoryId) ?: return null
        val oldVersion = findVersion(memoryId, versionId) ?: return null
        val newVersionId = insertVersion(oldVersion.copy(id = 0, reason = "restore", createdAt = restoredAt))

        val previousSources = findSources(oldVersion.id)
        val messageIds = previousSources.mapNotNull { source ->
            source.messageId?.takeUnless { source.status == MemorySourceStatus.SOURCE_DELETED }
        }.distinct()
        val currentContents = if (messageIds.isEmpty()) {
            emptyMap()
        } else {
            findMessageContents(messageIds).associate { it.id to it.content }
        }
        val restoredSources = previousSources.map { oldSource ->
            val priorMessageId = oldSource.messageId
            val currentContent = if (
                oldSource.status == MemorySourceStatus.SOURCE_DELETED || priorMessageId == null
            ) null else currentContents[priorMessageId]
            val deleted = currentContent == null
            MemorySourceEntity(
                memoryId = memoryId,
                versionId = newVersionId,
                messageId = if (deleted) null else priorMessageId,
                evidenceType = oldSource.evidenceType,
                position = oldSource.position,
                messageHash = oldSource.messageHash,
                status = when {
                    deleted -> MemorySourceStatus.SOURCE_DELETED
                    MemorySourceHash.sha256(currentContent!!) == oldSource.messageHash ->
                        MemorySourceStatus.SOURCE_CURRENT
                    else -> MemorySourceStatus.SOURCE_CHANGED
                },
                createdAt = restoredAt
            )
        }
        if (restoredSources.isNotEmpty()) insertSources(restoredSources)

        updateRecord(
            record.copy(
                name = oldVersion.name,
                description = oldVersion.description,
                kind = oldVersion.kind,
                source = oldVersion.source,
                pinned = oldVersion.pinned,
                updatedAt = restoredAt
            )
        )
        return findVersion(memoryId, newVersionId)
    }
}

data class AgentMessageContent(
    val id: String,
    val content: String
)
