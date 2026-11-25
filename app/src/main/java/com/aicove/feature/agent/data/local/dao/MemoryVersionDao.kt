package com.aicove.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aicove.feature.agent.data.local.entity.MemoryVersionEntity

@Dao
interface MemoryVersionDao {
    @Insert
    suspend fun insert(version: MemoryVersionEntity): Long

    @Query("SELECT * FROM memory_versions WHERE memoryId = :memoryId ORDER BY id DESC")
    suspend fun getVersionHistory(memoryId: String): List<MemoryVersionEntity>

    @Query("SELECT * FROM memory_versions WHERE memoryId = :memoryId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestVersion(memoryId: String): MemoryVersionEntity?

    @Query("SELECT * FROM memory_versions WHERE memoryId = :memoryId AND id = :versionId LIMIT 1")
    suspend fun getVersion(memoryId: String, versionId: Long): MemoryVersionEntity?
}
