package com.aicove.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.aicove.feature.agent.data.local.entity.MemorySourceEntity

@Dao
interface MemorySourceDao {
    @Query("SELECT * FROM memory_sources WHERE memoryId = :memoryId ORDER BY id ASC")
    suspend fun getSources(memoryId: String): List<MemorySourceEntity>

    @Query("SELECT * FROM memory_sources WHERE versionId = :versionId ORDER BY id ASC")
    suspend fun getSourcesForVersion(versionId: Long): List<MemorySourceEntity>

    @Query(
        """
        SELECT source.* FROM memory_sources AS source
        INNER JOIN memory_versions AS version ON version.id = source.versionId
        WHERE version.memoryId = :memoryId
          AND version.id = (
              SELECT id FROM memory_versions
              WHERE memoryId = :memoryId
              ORDER BY id DESC LIMIT 1
          )
        ORDER BY source.id ASC
        """
    )
    suspend fun getCurrentSources(memoryId: String): List<MemorySourceEntity>
}
