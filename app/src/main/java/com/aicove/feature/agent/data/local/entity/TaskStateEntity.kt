package com.aicove.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Project-scoped durable task state. projectKey is a locally normalized path for now. */
@Entity(
    tableName = "task_states",
    indices = [Index(value = ["projectKey", "status", "updatedAt"])]
)
data class TaskStateEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectKey: String,
    val goal: String,
    val status: String = TaskStateStatus.PENDING.name,
    val completedSteps: String = "[]",
    val pendingSteps: String = "[]",
    val blockedSteps: String = "[]",
    val constraints: String = "[]",
    val lastSessionId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

enum class TaskStateStatus {
    PENDING,
    RUNNING,
    BLOCKED,
    FAILED,
    RECOVERY_REQUIRED,
    COMPLETED,
    CANCELLED
}

/** Evidence references a tool message; its body is never copied into this row. */
@Entity(
    tableName = "task_evidence",
    indices = [
        Index(value = ["taskId", "verificationStatus"], name = "index_task_evidence_taskId_verificationStatus"),
        Index(value = ["sourceMessageId"], name = "index_task_evidence_sourceMessageId")
    ],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = TaskStateEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        ),
        androidx.room.ForeignKey(
            entity = AgentMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceMessageId"],
            onDelete = androidx.room.ForeignKey.SET_NULL
        )
    ]
)
data class TaskEvidenceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val sourceMessageId: String? = null,
    val sourceSessionId: String,
    val evidenceType: String,
    val verificationStatus: String,
    val sourceHash: String,
    val sourceStatus: String = TaskEvidenceSourceStatus.SOURCE_CURRENT.name,
    val createdAt: Long
)

enum class TaskEvidenceSourceStatus {
    SOURCE_CURRENT,
    SOURCE_DELETED,
    SOURCE_CHANGED
}

enum class TaskEvidenceVerificationStatus {
    PENDING,
    PASSED,
    FAILED,
    INVALIDATED
}

enum class TaskEvidenceType {
    TOOL_OBSERVATION,
    HOST_VALIDATION_COMMAND,
    HOST_VALIDATION_PASS
}
