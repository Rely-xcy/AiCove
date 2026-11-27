package com.aicove.feature.agent.presentation

import android.content.Context
import androidx.room.withTransaction
import com.aicove.R
import com.aicove.feature.agent.data.local.dao.AgentMessageDao
import com.aicove.feature.agent.data.local.database.AgentDatabase
import com.aicove.feature.agent.data.local.entity.AgentTurnRunEntity
import com.aicove.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class AgentTurnRecovery(
    private val database: AgentDatabase,
    private val messageDao: AgentMessageDao,
    private val messages: MessagePersistenceUseCase,
    private val context: Context
) {
    data class ResumeRequest(
        val userMessageId: String,
        val request: String,
        val modelRequest: String,
        val attachments: List<AgentAttachment>
    )

    fun observeInterrupted(sessionId: String?): Flow<AgentTurnRunEntity?> {
        if (sessionId.isNullOrBlank()) return flowOf(null)
        return database.agentTurnRunDao().observeBySessionId(sessionId).map { run ->
            run?.takeIf { it.status == AgentTurnRunEntity.INTERRUPTED }
        }
    }

    suspend fun interruptStaleRuns() {
        database.agentTurnRunDao().interruptStaleRuns(System.currentTimeMillis())
    }

    suspend fun cancelRunningRuns() {
        database.agentTurnRunDao().cancelRunningRuns(System.currentTimeMillis())
    }

    suspend fun claimInterrupted(sessionId: String): ResumeRequest? {
        val runDao = database.agentTurnRunDao()
        val run = runDao.getInterrupted(sessionId) ?: return null
        val userMessage = messageDao.getMessageById(run.userMessageId)
            ?.takeIf { it.sessionId == sessionId && it.role == MessageRole.USER.name }
            ?: return null
        val attachments = userMessage.toUIMessage().attachments
        val claimTime = System.currentTimeMillis()
        if (runDao.claimInterrupted(sessionId, run.userMessageId, claimTime) != 1) return null
        val modelRequest = if (attachments.isEmpty()) {
            userMessage.content
        } else {
            buildString {
                append(userMessage.content)
                if (userMessage.content.isNotBlank()) append("\\n\\n")
                append(context.getString(R.string.chat_attachment_prefix))
                attachments.forEach { attachment ->
                    append("\\n- ")
                    append(attachment.fileName)
                    append("：")
                    append(attachment.containerPath)
                }
            }
        }
        return ResumeRequest(run.userMessageId, userMessage.content, modelRequest, attachments)
    }

    suspend fun persistUserMessage(
        sessionId: String,
        userMessageId: String,
        request: String,
        attachments: List<AgentAttachment>,
        startsRootRun: Boolean
    ) {
        database.withTransaction {
            messages.persist(sessionId, MessageRole.USER, request, id = userMessageId, attachments = attachments)
            if (startsRootRun) {
                val now = System.currentTimeMillis()
                database.agentTurnRunDao().upsert(
                    AgentTurnRunEntity(
                        sessionId = sessionId,
                        userMessageId = userMessageId,
                        status = AgentTurnRunEntity.RUNNING,
                        startedAt = now,
                        updatedAt = now
                    )
                )
            }
        }
    }

    suspend fun cancel(sessionId: String, userMessageId: String?) {
        if (userMessageId == null) return
        withContext(NonCancellable + Dispatchers.IO) {
            database.agentTurnRunDao().markCancelled(sessionId, userMessageId, System.currentTimeMillis())
        }
    }

    suspend fun finish(sessionId: String, userMessageId: String?, finishStatus: String) {
        if (userMessageId == null) return
        val runDao = database.agentTurnRunDao()
        when (finishStatus) {
            "cancelled" -> runDao.markCancelled(sessionId, userMessageId, System.currentTimeMillis())
            "error" -> runDao.markFailed(sessionId, userMessageId, System.currentTimeMillis())
            else -> runDao.markCompleted(sessionId, userMessageId, System.currentTimeMillis())
        }
    }
}
