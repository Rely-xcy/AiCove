package com.aicove.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable lifecycle for one child-session run; current and pending turn inputs use separate columns. */
@Entity(
    tableName = "subagent_runs",
    indices = [Index(value = ["parentSessionId", "status"])],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subSessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentSessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SubagentRunEntity(
    @PrimaryKey val subSessionId: String,
    val parentSessionId: String,
    val status: String,
    val startedAt: Long,
    val updatedAt: Long,
    val terminationReason: String? = null,
    /** Input being processed by the current RUNNING turn; cleared only after its user row is persisted. */
    val claimedPrompt: String? = null,
    /** Pending initial or follow-up input, consumed only by the next atomic claim. */
    val initialPrompt: String? = null
)

enum class SubagentRunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

object SubagentRunTerminationReason {
    const val STOPPED = "STOPPED"
    const val RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
    const val MISSING_INITIAL_PROMPT = "MISSING_INITIAL_PROMPT"
    const val PARENT_SESSION_MISSING = "PARENT_SESSION_MISSING"
}
