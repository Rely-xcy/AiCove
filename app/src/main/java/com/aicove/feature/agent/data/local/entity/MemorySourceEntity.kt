package com.aicove.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

@Entity(
    tableName = "memory_sources",
    indices = [
        Index(value = ["memoryId", "versionId"]),
        Index(value = ["versionId"]),
        Index(value = ["messageId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MemoryVersionEntity::class,
            parentColumns = ["memoryId", "id"],
            childColumns = ["memoryId", "versionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AgentMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class MemorySourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryId: String,
    val versionId: Long,
    val messageId: String?,
    val evidenceType: String,
    val position: Int?,
    val messageHash: String,
    val status: String,
    val createdAt: Long
)

data class MemorySourceInput(
    val messageId: String,
    val evidenceType: String,
    val position: Int?,
    val messageHash: String
)

object MemorySourceStatus {
    const val SOURCE_CURRENT = "SOURCE_CURRENT"
    const val SOURCE_CHANGED = "SOURCE_CHANGED"
    const val SOURCE_DELETED = "SOURCE_DELETED"
}

object MemorySourceHash {
    fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }
}
