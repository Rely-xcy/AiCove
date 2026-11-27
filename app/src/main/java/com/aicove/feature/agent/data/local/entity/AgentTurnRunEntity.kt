package com.aicove.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_turn_runs",
    indices = [Index(value = ["userMessageId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AgentMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["userMessageId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AgentTurnRunEntity(
    @PrimaryKey val sessionId: String,
    val userMessageId: String,
    val status: String,
    val startedAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val RUNNING = "RUNNING"
        const val INTERRUPTED = "INTERRUPTED"
        const val COMPLETED = "COMPLETED"
        const val CANCELLED = "CANCELLED"
        const val FAILED = "FAILED"
    }
}
