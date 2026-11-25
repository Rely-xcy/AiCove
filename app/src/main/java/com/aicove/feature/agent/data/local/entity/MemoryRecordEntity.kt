package com.aicove.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_records",
    indices = [
        Index(value = ["markdownPath"], unique = true),
        Index(value = ["scope", "name"])
    ]
)
data class MemoryRecordEntity(
    @PrimaryKey val id: String,
    val markdownPath: String?,
    val name: String,
    val description: String,
    val scope: String,
    val kind: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val hitCount: Int,
    val lastHitAt: Long,
    val pinned: Boolean
)
