package com.aicove.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.aicove.feature.agent.data.local.entity.AgentTurnRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentTurnRunDao {
    @Upsert
    suspend fun upsert(run: AgentTurnRunEntity)

    @Query("SELECT * FROM agent_turn_runs WHERE sessionId = :sessionId")
    fun observeBySessionId(sessionId: String): Flow<AgentTurnRunEntity?>

    @Query("SELECT * FROM agent_turn_runs WHERE sessionId = :sessionId AND status = 'INTERRUPTED'")
    suspend fun getInterrupted(sessionId: String): AgentTurnRunEntity?

    @Query("UPDATE agent_turn_runs SET status = 'INTERRUPTED', updatedAt = :now WHERE status = 'RUNNING'")
    suspend fun interruptStaleRuns(now: Long): Int

    @Query("UPDATE agent_turn_runs SET status = 'COMPLETED', updatedAt = :now WHERE sessionId = :sessionId AND userMessageId = :userMessageId AND status = 'RUNNING'")
    suspend fun markCompleted(sessionId: String, userMessageId: String, now: Long): Int

    @Query("UPDATE agent_turn_runs SET status = 'FAILED', updatedAt = :now WHERE sessionId = :sessionId AND userMessageId = :userMessageId AND status = 'RUNNING'")
    suspend fun markFailed(sessionId: String, userMessageId: String, now: Long): Int

    @Query("UPDATE agent_turn_runs SET status = 'CANCELLED', updatedAt = :now WHERE status = 'RUNNING'")
    suspend fun cancelRunningRuns(now: Long): Int

    @Query("UPDATE agent_turn_runs SET status = 'CANCELLED', updatedAt = :now WHERE sessionId = :sessionId AND userMessageId = :userMessageId AND status = 'RUNNING'")
    suspend fun markCancelled(sessionId: String, userMessageId: String, now: Long): Int

    @Query("UPDATE agent_turn_runs SET status = 'RUNNING', startedAt = :now, updatedAt = :now WHERE sessionId = :sessionId AND userMessageId = :userMessageId AND status = 'INTERRUPTED'")
    suspend fun claimInterrupted(sessionId: String, userMessageId: String, now: Long): Int
}
