package com.aicove.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_versions",
    indices = [
        Index(value = ["memoryId", "createdAt"]),
        Index(value = ["memoryId", "id"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MemoryVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryId: String,
    val content: String,
    val name: String,
    val description: String,
    val kind: String,
    val source: String,
    val pinned: Boolean,
    val reason: String,
    val createdAt: Long
)
