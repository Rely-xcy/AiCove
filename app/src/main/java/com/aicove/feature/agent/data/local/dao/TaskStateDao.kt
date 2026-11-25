package com.aicove.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.aicove.feature.agent.data.local.entity.TaskEvidenceEntity
import com.aicove.feature.agent.data.local.entity.TaskEvidenceSourceStatus
import com.aicove.feature.agent.data.local.entity.TaskEvidenceType
import com.aicove.feature.agent.data.local.entity.TaskEvidenceVerificationStatus
import com.aicove.feature.agent.data.local.entity.TaskStateEntity
import com.aicove.feature.agent.data.local.entity.TaskStateStatus
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Dao
abstract class TaskStateDao {
    /** Saves a task without REPLACE, which would delete the old row and cascade its evidence. */
    @Transaction
    open suspend fun saveTask(task: TaskStateEntity) {
        require(task.projectKey.isNotBlank()) { "projectKey must not be blank" }
        require(task.status in TaskStateStatus.values().map { it.name }) {
            "Unknown task state: ${task.status}"
        }
        require(task.status != TaskStateStatus.COMPLETED.name) {
            "COMPLETED can only be set by completeIfVerified"
        }
        val normalizedTask = task.copy(projectKey = TaskProjectKey.normalize(task.projectKey))
        if (getTaskById(normalizedTask.id) == null) insertTask(normalizedTask)
        else check(updateTask(normalizedTask) == 1) { "Task ${task.id} disappeared while being updated" }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTask(task: TaskStateEntity)

    @Update
    protected abstract suspend fun updateTask(task: TaskStateEntity): Int

    @Query("SELECT * FROM task_states WHERE id = :taskId LIMIT 1")
    abstract suspend fun getTaskById(taskId: String): TaskStateEntity?

    @Query(
        """
        SELECT * FROM task_states
        WHERE projectKey = :projectKey AND status NOT IN ('COMPLETED', 'CANCELLED')
        ORDER BY updatedAt DESC, id ASC
        """
    )
    protected abstract suspend fun getUnfinishedTasksByNormalizedKey(projectKey: String): List<TaskStateEntity>

    suspend fun getUnfinishedTasks(projectKey: String): List<TaskStateEntity> =
        getUnfinishedTasksByNormalizedKey(TaskProjectKey.normalize(projectKey))

    @Query(
        """
        SELECT * FROM task_states
        WHERE projectKey = :projectKey AND status NOT IN ('COMPLETED', 'CANCELLED')
        ORDER BY updatedAt DESC, id ASC
        """
    )
    protected abstract fun observeUnfinishedTasksByNormalizedKey(projectKey: String): kotlinx.coroutines.flow.Flow<List<TaskStateEntity>>

    fun observeUnfinishedTasks(projectKey: String): kotlinx.coroutines.flow.Flow<List<TaskStateEntity>> =
        observeUnfinishedTasksByNormalizedKey(TaskProjectKey.normalize(projectKey))

    /** Normal status transitions cannot assign COMPLETED. */
    @Query(
        """
        UPDATE task_states SET status = :status, updatedAt = :updatedAt, lastSessionId = :lastSessionId
        WHERE id = :taskId AND :status IN
            ('PENDING', 'RUNNING', 'BLOCKED', 'FAILED', 'RECOVERY_REQUIRED', 'CANCELLED')
        """
    )
    abstract suspend fun updateStatus(
        taskId: String,
        status: String,
        updatedAt: Long,
        lastSessionId: String?
    ): Int

    /** Saves a reference only; a transcript/model claim cannot assign PASSED. */
    @Transaction
    open suspend fun addEvidence(evidence: TaskEvidenceEntity) {
        require(evidence.verificationStatus in listOf(
            TaskEvidenceVerificationStatus.PENDING.name,
            TaskEvidenceVerificationStatus.FAILED.name,
            TaskEvidenceVerificationStatus.INVALIDATED.name
        )) { "PASSED evidence must be recorded through recordHostValidation" }
        require(evidence.sourceStatus in TaskEvidenceSourceStatus.values().map { it.name }) {
            "Unknown evidence source status: ${evidence.sourceStatus}"
        }
        require(evidence.evidenceType in TaskEvidenceType.values().map { it.name }) {
            "Unknown evidence type: ${evidence.evidenceType}"
        }
        insertEvidenceRow(evidence)
    }

    /** Records a foreground host Bash validation only when the host reports real exitCode=0. */
    @Transaction
    open suspend fun recordHostValidation(
        evidence: TaskEvidenceEntity,
        command: String,
        exitCode: Int?,
        cancelled: Boolean,
        timedOut: Boolean
    ): Boolean {
        if (evidence.evidenceType != TaskEvidenceType.HOST_VALIDATION_PASS.name ||
            exitCode != 0 || cancelled || timedOut ||
            !isRecognizedValidationCommand(command)
        ) return false

        val pending = evidence.copy(verificationStatus = TaskEvidenceVerificationStatus.PENDING.name)
        insertEvidenceRow(pending)
        val candidate = getVerificationCandidateByEvidenceId(pending.id)
        if (candidate == null || !candidate.isValidHostValidationSource(command)) {
            deleteEvidenceRow(pending.id)
            return false
        }
        if (markEvidencePassedRow(
                pending.id,
                candidate.taskProjectKey,
                candidate.workspacePath,
                candidate.toolArgs
            ) != 1
        ) {
            deleteEvidenceRow(pending.id)
            return false
        }
        return true
    }

    private fun isRecognizedValidationCommand(command: String): Boolean {
        val normalized = command.trim()
        // Do not accept command chaining, pipelines, backgrounding, substitutions, or redirection:
        // a successful trailing echo must not turn a failed test into validation evidence.
        if (normalized.isEmpty() || normalized.contains('\n') || normalized.contains('\r') ||
            normalized.any { it in ";|&<>`$" || it == '\\' }
        ) return false
        val firstSpace = normalized.indexOf(' ')
        val executable = if (firstSpace < 0) normalized else normalized.substring(0, firstSpace)
        val args = if (firstSpace < 0) "" else normalized.substring(firstSpace + 1).trimStart()
        val firstArg = args.substringBefore(' ')
        fun isGradleTask(prefix: String): Boolean =
            firstArg == prefix || (firstArg.startsWith(prefix) && firstArg.getOrNull(prefix.length)?.isUpperCase() == true)
        return when (executable) {
            "./gradlew", "gradle" -> isGradleTask("test") || isGradleTask("check") ||
                isGradleTask("lint") || isGradleTask("build")
            "pytest" -> firstArg.isEmpty() || firstArg.startsWith("-")
            "python", "python3" -> args == "-m pytest" || args.startsWith("-m pytest ")
            "npm" -> {
                val script = if (args.startsWith("run ")) args.removePrefix("run ").substringBefore(' ') else args
                script in setOf("test", "check", "lint") || script.startsWith("test:") ||
                    script.startsWith("check:") || script.startsWith("lint:")
            }
            "yarn", "pnpm" -> {
                val script = args.substringBefore(' ')
                script in setOf("test", "check", "lint") || script.startsWith("test:") ||
                    script.startsWith("check:") || script.startsWith("lint:")
            }
            "cargo" -> firstArg in setOf("test", "check", "build")
            "go" -> firstArg in setOf("test", "build")
            "make" -> firstArg in setOf("test", "check")
            else -> false
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvidenceRow(evidence: TaskEvidenceEntity)

    @Query("DELETE FROM task_evidence WHERE id = :evidenceId")
    protected abstract suspend fun deleteEvidenceRow(evidenceId: String): Int

    @Query("SELECT * FROM task_evidence WHERE id = :evidenceId LIMIT 1")
    abstract suspend fun getEvidence(evidenceId: String): TaskEvidenceEntity?

    @Query("SELECT * FROM task_evidence WHERE taskId = :taskId ORDER BY createdAt, id")
    abstract suspend fun getEvidenceForTask(taskId: String): List<TaskEvidenceEntity>

    @Query(
        """
        SELECT e.taskId AS taskId, e.id AS evidenceId, e.sourceMessageId AS sourceMessageId,
               e.sourceSessionId AS evidenceSessionId, e.sourceStatus AS sourceStatus,
               e.verificationStatus AS verificationStatus, e.sourceHash AS sourceHash,
               e.evidenceType AS evidenceType, t.projectKey AS taskProjectKey,
               m.sessionId AS messageSessionId, m.role AS role, m.toolName AS toolName,
               m.content AS content, m.toolArgs AS toolArgs, m.isError AS isError,
               s.workspacePath AS workspacePath
        FROM task_evidence AS e
        INNER JOIN task_states AS t ON t.id = e.taskId
        INNER JOIN agent_messages AS m ON m.id = e.sourceMessageId
        INNER JOIN chat_sessions AS s ON s.id = m.sessionId
        WHERE e.id = :evidenceId AND e.taskId = :taskId
        LIMIT 1
        """
    )
    protected abstract suspend fun getVerificationCandidate(
        taskId: String,
        evidenceId: String
    ): TaskEvidenceVerificationCandidate?

    @Query(
        """
        SELECT e.taskId AS taskId, e.id AS evidenceId, e.sourceMessageId AS sourceMessageId,
               e.sourceSessionId AS evidenceSessionId, e.sourceStatus AS sourceStatus,
               e.verificationStatus AS verificationStatus, e.sourceHash AS sourceHash,
               e.evidenceType AS evidenceType, t.projectKey AS taskProjectKey,
               m.sessionId AS messageSessionId, m.role AS role, m.toolName AS toolName,
               m.content AS content, m.toolArgs AS toolArgs, m.isError AS isError,
               s.workspacePath AS workspacePath
        FROM task_evidence AS e
        INNER JOIN task_states AS t ON t.id = e.taskId
        INNER JOIN agent_messages AS m ON m.id = e.sourceMessageId
        INNER JOIN chat_sessions AS s ON s.id = m.sessionId
        WHERE e.id = :evidenceId LIMIT 1
        """
    )
    protected abstract suspend fun getVerificationCandidateByEvidenceId(
        evidenceId: String
    ): TaskEvidenceVerificationCandidate?

    @Query(
        """
        UPDATE task_evidence SET verificationStatus = 'PASSED'
        WHERE id = :evidenceId AND sourceStatus = 'SOURCE_CURRENT'
          AND evidenceType = 'HOST_VALIDATION_PASS'
          AND verificationStatus = 'PENDING'
          AND sourceMessageId IS NOT NULL
          AND EXISTS (
              SELECT 1 FROM agent_messages AS m
              INNER JOIN chat_sessions AS s ON s.id = m.sessionId
              INNER JOIN task_states AS t ON t.id = task_evidence.taskId
              WHERE m.id = task_evidence.sourceMessageId
                AND m.sessionId = task_evidence.sourceSessionId
                AND m.role = 'TOOL' AND m.toolName = 'Bash' AND m.isError = 0
                AND m.content NOT LIKE '[running]%'
                AND m.content NOT LIKE '⏳%'
                AND t.projectKey = :projectKey
                AND s.workspacePath = :workspacePath
                AND m.toolArgs = :toolArgs
          )
        """
    )
    protected abstract suspend fun markEvidencePassedRow(
        evidenceId: String,
        projectKey: String,
        workspacePath: String,
        toolArgs: String?
    ): Int

    /**
     * Completion is deliberately a separate transaction, not an upsert/status setter. It requires
     * current PASSED evidence linked to an existing, successful TOOL row whose body hash still
     * matches. The task/evidence/source rows are checked in one Room transaction.
     */
    @Transaction
    open suspend fun completeIfVerified(taskId: String, evidenceId: String): Boolean {
        val candidate = getVerificationCandidate(taskId, evidenceId) ?: return false
        if (!candidate.isValidHostValidationSource() ||
            candidate.verificationStatus != TaskEvidenceVerificationStatus.PASSED.name ||
            candidate.content == null ||
            candidate.content.startsWith("[running]") || candidate.content.startsWith("⏳") ||
            candidate.sourceHash != TaskEvidenceHash.sha256(candidate.content, candidate.toolArgs)
        ) return false

        return markTaskCompletedIfVerified(
            taskId,
            evidenceId,
            candidate.sourceHash,
            System.currentTimeMillis(),
            candidate.taskProjectKey,
            candidate.workspacePath,
            candidate.toolArgs
        ) == 1
    }

    @Query(
        """
        UPDATE task_states SET status = 'COMPLETED', updatedAt = :completedAt
        WHERE id = :taskId AND status NOT IN ('COMPLETED', 'CANCELLED')
          AND EXISTS (
              SELECT 1 FROM task_evidence AS e
              INNER JOIN agent_messages AS m ON m.id = e.sourceMessageId
              INNER JOIN chat_sessions AS s ON s.id = m.sessionId
              WHERE e.id = :evidenceId AND e.taskId = task_states.id
                AND e.sourceStatus = 'SOURCE_CURRENT'
                AND e.verificationStatus = 'PASSED'
                AND e.evidenceType = 'HOST_VALIDATION_PASS'
                AND e.sourceHash = :verifiedSourceHash
                AND e.sourceMessageId IS NOT NULL
                AND e.sourceSessionId = m.sessionId
                AND m.role = 'TOOL' AND m.toolName = 'Bash' AND m.isError = 0
                AND m.content NOT LIKE '[running]%'
                AND m.content NOT LIKE '⏳%'
                AND s.workspacePath = :workspacePath
                AND m.toolArgs = :toolArgs
                AND EXISTS (
                    SELECT 1 FROM task_states AS t
                    WHERE t.id = task_states.id AND t.projectKey = :projectKey
                )
          )
        """
    )
    protected abstract suspend fun markTaskCompletedIfVerified(
        taskId: String,
        evidenceId: String,
        verifiedSourceHash: String,
        completedAt: Long,
        projectKey: String,
        workspacePath: String,
        toolArgs: String?
    ): Int
}

data class TaskEvidenceVerificationCandidate(
    val taskId: String,
    val evidenceId: String,
    val sourceMessageId: String?,
    val evidenceSessionId: String,
    val sourceStatus: String,
    val verificationStatus: String,
    val sourceHash: String,
    val evidenceType: String,
    val taskProjectKey: String,
    val messageSessionId: String,
    val role: String,
    val toolName: String?,
    val content: String?,
    val toolArgs: String?,
    val isError: Int,
    val workspacePath: String
)

private fun TaskEvidenceVerificationCandidate.isValidHostValidationSource(
    expectedCommand: String? = null
): Boolean =
    sourceMessageId != null &&
        sourceStatus == TaskEvidenceSourceStatus.SOURCE_CURRENT.name &&
        evidenceType == TaskEvidenceType.HOST_VALIDATION_PASS.name &&
        evidenceSessionId == messageSessionId &&
        role == "TOOL" && toolName == "Bash" && isError == 0 &&
        taskProjectKey == TaskProjectKey.normalize(workspacePath) &&
        content != null && !content.startsWith("[running]") && !content.startsWith("⏳") &&
        (expectedCommand == null || storedCommand() == expectedCommand) &&
        storedCommand()?.let(::isRecognizedHostValidationCommand) == true &&
        sourceHash == TaskEvidenceHash.sha256(content, toolArgs)

object TaskProjectKey {
    /** Canonical local path; remote keys can later be injected as connection-id/path pairs. */
    fun normalize(path: String): String = runCatching { File(path).canonicalFile.path }
        .getOrElse { File(path).absoluteFile.path }
}

private fun TaskEvidenceVerificationCandidate.storedCommand(): String? = runCatching {
    val json = toolArgs ?: return null
    Json.parseToJsonElement(json).jsonObject["command"]?.jsonPrimitive?.contentOrNull
}.getOrNull()

private fun isRecognizedHostValidationCommand(command: String): Boolean {
    val normalized = command.trim()
    if (normalized.isEmpty() || normalized.contains('\n') || normalized.contains('\r') ||
        normalized.any { it in ";|&<>`$" || it == '\\' }
    ) return false
    val separator = normalized.indexOf(' ')
    val executable = if (separator < 0) normalized else normalized.substring(0, separator)
    val args = if (separator < 0) "" else normalized.substring(separator + 1).trimStart()
    val firstArg = args.substringBefore(' ')
    fun gradleTask(prefix: String) =
        firstArg == prefix || (firstArg.startsWith(prefix) && firstArg.getOrNull(prefix.length)?.isUpperCase() == true)
    return when (executable) {
        "./gradlew", "gradle" -> gradleTask("test") || gradleTask("check") || gradleTask("lint") || gradleTask("build")
        "pytest" -> firstArg.isEmpty() || firstArg.startsWith("-")
        "python", "python3" -> args == "-m pytest" || args.startsWith("-m pytest ")
        "npm" -> {
            val script = if (args.startsWith("run ")) args.removePrefix("run ").substringBefore(' ') else args
            script in setOf("test", "check", "lint") || script.startsWith("test:") || script.startsWith("check:") || script.startsWith("lint:")
        }
        "yarn", "pnpm" -> {
            val script = args.substringBefore(' ')
            script in setOf("test", "check", "lint") || script.startsWith("test:") || script.startsWith("check:") || script.startsWith("lint:")
        }
        "cargo" -> firstArg in setOf("test", "check", "build")
        "go" -> firstArg in setOf("test", "build")
        "make" -> firstArg in setOf("test", "check")
        else -> false
    }
}

object TaskEvidenceHash {
    fun sha256(content: String, toolArgs: String? = null): String {
        val bytes = "$content\\u0000${toolArgs.orEmpty()}".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
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
