package com.aicove.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aicove.feature.agent.data.local.entity.ChatSessionEntity
import com.aicove.feature.agent.data.local.entity.SubagentRunEntity
import com.aicove.feature.agent.data.local.entity.SubagentRunStatus
import com.aicove.feature.agent.data.local.entity.SubagentRunTerminationReason

@Dao
abstract class SubagentRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRun(run: SubagentRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRun(run: SubagentRunEntity)

    @Query("SELECT * FROM subagent_runs WHERE subSessionId = :subSessionId")
    abstract suspend fun getBySubSessionId(subSessionId: String): SubagentRunEntity?

    @Query("SELECT * FROM subagent_runs WHERE parentSessionId = :parentSessionId")
    abstract suspend fun getByParentSessionId(parentSessionId: String): List<SubagentRunEntity>

    @Query("SELECT * FROM subagent_runs WHERE status = 'QUEUED' ORDER BY startedAt")
    abstract suspend fun getQueuedRuns(): List<SubagentRunEntity>

    @Query("SELECT * FROM subagent_runs WHERE status = 'RUNNING'")
    abstract suspend fun getRunningRuns(): List<SubagentRunEntity>

    /** Inserts the child session and QUEUED run atomically before SPAWNED is emitted. */
    @Transaction
    open suspend fun createQueuedRun(session: ChatSessionEntity, run: SubagentRunEntity) {
        require(session.id == run.subSessionId)
        require(session.parentId == run.parentSessionId)
        require(run.status == SubagentRunStatus.QUEUED.name)
        insertSession(session)
        insertRun(run)
    }

    /** CAS claim copies pending input into claimedPrompt and retains it until DB confirms the user row. */
    @Query(
        "UPDATE subagent_runs SET status = 'RUNNING', startedAt = :updatedAt, updatedAt = :updatedAt, " +
            "terminationReason = NULL, claimedPrompt = initialPrompt, initialPrompt = NULL " +
            "WHERE subSessionId = :subSessionId AND status = 'QUEUED' " +
            "AND initialPrompt IS NOT NULL AND trim(initialPrompt) != ''"
    )
    protected abstract suspend fun claimQueuedRunRow(subSessionId: String, updatedAt: Long): Int

    @Query("UPDATE subagent_runs SET claimedPrompt = NULL, updatedAt = :updatedAt WHERE subSessionId = :subSessionId AND status = 'RUNNING' AND claimedPrompt = :prompt")
    abstract suspend fun clearClaimedPrompt(subSessionId: String, prompt: String, updatedAt: Long = System.currentTimeMillis()): Int

    /** Atomically claim one queued turn while preserving the exact input until it is persisted. */
    @Transaction
    open suspend fun claimQueuedPrompt(subSessionId: String, updatedAt: Long): String? {
        val queued = getBySubSessionId(subSessionId) ?: return null
        if (queued.status != SubagentRunStatus.QUEUED.name) return null
        val prompt = queued.initialPrompt?.takeIf { it.isNotBlank() } ?: return null
        return prompt.takeIf { claimQueuedRunRow(subSessionId, updatedAt) == 1 }
    }

    /** Persist follow-up separately; a running turn is not made QUEUED until its normal finish. */
    @Transaction
    open suspend fun queueContinuation(
        subSessionId: String,
        parentSessionId: String,
        prompt: String,
        now: Long
    ): String {
        val existing = getBySubSessionId(subSessionId)
        if (existing?.status == SubagentRunStatus.QUEUED.name) {
            appendQueuedPrompt(subSessionId, SubagentRunStatus.QUEUED.name, prompt, now)
            return SubagentRunStatus.QUEUED.name
        }
        if (existing?.status == SubagentRunStatus.RUNNING.name) {
            appendQueuedPrompt(subSessionId, SubagentRunStatus.RUNNING.name, prompt, now)
            return SubagentRunStatus.RUNNING.name
        }
        if (existing != null &&
            existing.status in setOf(SubagentRunStatus.FAILED.name, SubagentRunStatus.INTERRUPTED.name) &&
            !existing.initialPrompt.isNullOrBlank()
        ) {
            // A failed/interrupted continuation requires the explicit recovery method; do not replace it.
            return existing!!.status
        }
        val queued = (existing ?: SubagentRunEntity(
            subSessionId = subSessionId,
            parentSessionId = parentSessionId,
            status = SubagentRunStatus.QUEUED.name,
            startedAt = 0L,
            updatedAt = now
        )).copy(
            parentSessionId = parentSessionId,
            status = SubagentRunStatus.QUEUED.name,
            startedAt = 0L,
            updatedAt = now,
            terminationReason = null,
            initialPrompt = prompt
        )
        upsertRun(queued)
        return SubagentRunStatus.QUEUED.name
    }

    @Query("UPDATE subagent_runs SET status = 'FAILED', updatedAt = :updatedAt, terminationReason = :reason, claimedPrompt = NULL WHERE subSessionId = :subSessionId AND status = 'RUNNING'")
    abstract suspend fun markFailedPreservingFollowup(
        subSessionId: String,
        reason: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE subagent_runs SET status = 'QUEUED', updatedAt = :updatedAt, terminationReason = NULL, claimedPrompt = NULL WHERE subSessionId = :subSessionId AND status IN ('FAILED', 'INTERRUPTED') AND initialPrompt IS NOT NULL AND trim(initialPrompt) != ''")
    protected abstract suspend fun promotePreservedFollowupAfterExplicitSend(
        subSessionId: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /** Explicitly acknowledge the earlier failure before starting the preserved pending instruction. */
    @Transaction
    open suspend fun resumeFailedRunWithExplicitMessage(
        subSessionId: String,
        parentSessionId: String,
        prompt: String,
        now: Long
    ): String {
        val existing = getBySubSessionId(subSessionId)
        if (existing != null &&
            existing.status in setOf(SubagentRunStatus.FAILED.name, SubagentRunStatus.INTERRUPTED.name) &&
            !existing.initialPrompt.isNullOrBlank()
        ) {
            appendQueuedPrompt(subSessionId, existing.status, prompt, now)
            check(promotePreservedFollowupAfterExplicitSend(subSessionId, now) == 1)
            return SubagentRunStatus.QUEUED.name
        }
        return queueContinuation(subSessionId, parentSessionId, prompt, now)
    }

    /** On normal finish, expose a pending continuation as a claimable QUEUED turn atomically. */
    @Query(
        "UPDATE subagent_runs SET status = CASE WHEN initialPrompt IS NULL OR trim(initialPrompt) = '' " +
            "THEN 'COMPLETED' ELSE 'QUEUED' END, updatedAt = :updatedAt, " +
            "terminationReason = NULL, startedAt = CASE WHEN initialPrompt IS NULL OR trim(initialPrompt) = '' " +
            "THEN startedAt ELSE 0 END, claimedPrompt = NULL " +
            "WHERE subSessionId = :subSessionId AND status = 'RUNNING'"
    )
    protected abstract suspend fun finishRunningRow(subSessionId: String, updatedAt: Long): Int

    @Transaction
    open suspend fun finishRun(subSessionId: String, updatedAt: Long = System.currentTimeMillis()): String? {
        if (finishRunningRow(subSessionId, updatedAt) != 1) return null
        return getBySubSessionId(subSessionId)?.status
    }

    @Query(
        "UPDATE subagent_runs SET initialPrompt = CASE " +
            "WHEN initialPrompt IS NULL OR initialPrompt = '' THEN :prompt " +
            "ELSE initialPrompt || char(10) || char(10) || '[Additional message]' || char(10) || :prompt END, " +
            "updatedAt = :updatedAt WHERE subSessionId = :subSessionId AND status = :status"
    )
    protected abstract suspend fun appendQueuedPrompt(
        subSessionId: String,
        status: String,
        prompt: String,
        updatedAt: Long
    )

    @Query(
        "UPDATE subagent_runs SET status = 'FAILED', updatedAt = :updatedAt, terminationReason = :reason, " +
            "claimedPrompt = NULL, initialPrompt = NULL " +
            "WHERE subSessionId = :subSessionId AND status IN ('QUEUED', 'RUNNING')"
    )
    abstract suspend fun markFailed(
        subSessionId: String,
        reason: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int


    @Query(
        "UPDATE subagent_runs SET status = 'CANCELLED', updatedAt = :updatedAt, terminationReason = :reason, " +
            "claimedPrompt = NULL, initialPrompt = NULL " +
            "WHERE subSessionId = :subSessionId AND status IN ('QUEUED', 'RUNNING', 'FAILED', 'INTERRUPTED')"
    )
    abstract suspend fun markCancelled(
        subSessionId: String,
        reason: String = SubagentRunTerminationReason.STOPPED,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        "UPDATE subagent_runs SET status = 'CANCELLED', updatedAt = :updatedAt, terminationReason = :reason, " +
            "claimedPrompt = NULL, initialPrompt = NULL " +
            "WHERE subSessionId = :subSessionId AND status = 'RUNNING'"
    )
    abstract suspend fun markCancelledIfRunning(
        subSessionId: String,
        reason: String = SubagentRunTerminationReason.STOPPED,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        "UPDATE subagent_runs SET status = 'CANCELLED', updatedAt = :updatedAt, " +
            "claimedPrompt = NULL, initialPrompt = NULL, " +
            "terminationReason = '${SubagentRunTerminationReason.STOPPED}' " +
            "WHERE status IN ('QUEUED', 'RUNNING', 'FAILED', 'INTERRUPTED')"
    )
    abstract suspend fun markAllPendingCancelled(updatedAt: Long = System.currentTimeMillis()): Int

    /** Cold-start recovery never requeues or replays RUNNING side effects. */
    @Query(
        "UPDATE subagent_runs SET status = 'INTERRUPTED', updatedAt = :updatedAt, " +
            "terminationReason = '${SubagentRunTerminationReason.RECOVERY_REQUIRED}' " +
            "WHERE subSessionId = :subSessionId AND status = 'RUNNING'"
    )
    abstract suspend fun interruptRunningRun(
        subSessionId: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        "UPDATE subagent_runs SET status = 'INTERRUPTED', updatedAt = :updatedAt, " +
            "terminationReason = '${SubagentRunTerminationReason.RECOVERY_REQUIRED}' " +
            "WHERE status = 'RUNNING'"
    )
    abstract suspend fun interruptRunningRuns(updatedAt: Long = System.currentTimeMillis()): Int

    @Query(
        "UPDATE subagent_runs SET status = 'INTERRUPTED', updatedAt = :updatedAt, " +
            "terminationReason = '${SubagentRunTerminationReason.MISSING_INITIAL_PROMPT}' " +
            "WHERE status = 'QUEUED' AND (initialPrompt IS NULL OR trim(initialPrompt) = '')"
    )
    abstract suspend fun interruptQueuedRunsWithoutPrompt(updatedAt: Long = System.currentTimeMillis()): Int

    @Query(
        "UPDATE subagent_runs SET status = 'INTERRUPTED', updatedAt = :updatedAt, " +
            "terminationReason = '${SubagentRunTerminationReason.PARENT_SESSION_MISSING}' " +
            "WHERE status = 'QUEUED' AND NOT EXISTS " +
            "(SELECT 1 FROM chat_sessions WHERE chat_sessions.id = subagent_runs.parentSessionId)"
    )
    abstract suspend fun interruptQueuedRunsWithoutParent(updatedAt: Long = System.currentTimeMillis()): Int
}
