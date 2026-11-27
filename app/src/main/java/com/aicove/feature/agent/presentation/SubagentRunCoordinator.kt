package com.aicove.feature.agent.presentation

import com.aicove.feature.agent.data.local.dao.ChatSessionDao
import com.aicove.feature.agent.data.local.dao.SubagentRunDao
import com.aicove.feature.agent.data.local.entity.ChatSessionEntity
import com.aicove.feature.agent.data.local.entity.SubagentRunStatus
import com.aicove.feature.agent.data.local.entity.SubagentRunTerminationReason
import com.aicove.feature.agent.domain.subagent.SubAgentEvent
import com.aicove.feature.agent.domain.subagent.SubAgentEventBus
import com.aicove.feature.agent.domain.subagent.SubAgentEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext

/** Coordinates durable child-run recovery and single-consumer prompt claims for the ViewModel. */
internal class SubagentRunCoordinator(
    runDaoProvider: Lazy<SubagentRunDao>,
    private val chatSessionDao: ChatSessionDao,
    private val eventBus: SubAgentEventBus
) {
    private val runDao by runDaoProvider

    class StartRequest(
        val parent: ChatSessionEntity,
        val prompt: String,
        val onMessagePersisted: suspend () -> Unit
    )

    // Recovery only re-emits QUEUED rows; the subscription is attached before the scan.
    // RUNNING rows become INTERRUPTED and their prompts/tool side effects are never replayed.
    val events: Flow<SubAgentEvent>
        get() = eventBus.events.onSubscription {
            withContext(Dispatchers.IO) { recoverQueuedRuns(runDao) }
        }.onEach { event ->
            if (event.type == SubAgentEventType.STOPPED) {
                withContext(Dispatchers.IO) { markCancelled(event.subSessionId) }
            }
        }

    private suspend fun recoverQueuedRuns(runDao: SubagentRunDao) {
        val now = System.currentTimeMillis()
        val activeIds = eventBus.activeSubSessionIds.value
        runDao.getRunningRuns().filterNot { it.subSessionId in activeIds }.forEach { run ->
            runDao.interruptRunningRun(run.subSessionId, now)
        }
        runDao.interruptQueuedRunsWithoutPrompt(now)
        runDao.interruptQueuedRunsWithoutParent(now)
        runDao.getQueuedRuns().forEach { run ->
            if (run.initialPrompt.isNullOrBlank()) return@forEach
            val event = SubAgentEvent(run.subSessionId, run.parentSessionId, SubAgentEventType.SPAWNED)
            if (!eventBus.emitWake(event)) return@forEach
        }
    }

    /** Validate ownership rows and atomically claim one queued prompt before workflow startup. */
    suspend fun claimForStart(event: SubAgentEvent): StartRequest? {
        val run = runDao.getBySubSessionId(event.subSessionId)
        if (run?.status == SubagentRunStatus.RUNNING.name && run.parentSessionId == event.parentSessionId) {
            // A duplicate SPAWNED can race the first claim. Preserve the first run's slot.
            eventBus.retainActive(event.subSessionId)
            return null
        }
        if (run == null || run.parentSessionId != event.parentSessionId ||
            run.status != SubagentRunStatus.QUEUED.name || run.initialPrompt.isNullOrBlank()
        ) {
            eventBus.release(event.subSessionId)
            return null
        }
        val parent = chatSessionDao.getById(event.parentSessionId)
        if (parent == null) {
            runDao.markFailed(event.subSessionId, SubagentRunTerminationReason.PARENT_SESSION_MISSING)
            eventBus.release(event.subSessionId)
            return null
        }
        if (chatSessionDao.getById(event.subSessionId) == null) {
            eventBus.release(event.subSessionId)
            return null
        }
        val prompt = runDao.claimQueuedPrompt(event.subSessionId, System.currentTimeMillis()) ?: return null
        // The current prompt remains durable across history reconstruction and the first DB insert.
        // Clear it only when the user row is committed, never merely when SPAWNED was emitted.
        // Follow-up input lives separately in initialPrompt and survives this acknowledgement.
        // A cold-start RUNNING -> INTERRUPTED row is diagnostic only; neither prompt is auto-run.
        // This avoids retrying tools that may have completed after the durable claim was made.
        // Explicit send after interruption replaces the old intent with new user-provided work.
        return StartRequest(parent, prompt) {
            withContext(Dispatchers.IO) { runDao.clearClaimedPrompt(event.subSessionId, prompt) }
        }
    }

    /** Drain durable queued turns whenever any child releases its slot. */
    suspend fun wakeQueuedRun(subSessionId: String) {
        val queued = runDao.getQueuedRuns().sortedBy { it.subSessionId != subSessionId }
        for (run in queued) {
            if (run.initialPrompt.isNullOrBlank()) continue
            if (chatSessionDao.getById(run.parentSessionId) == null) {
                runDao.interruptQueuedRunsWithoutParent()
                continue
            }
            if (!eventBus.emitWake(
                    SubAgentEvent(run.subSessionId, run.parentSessionId, SubAgentEventType.SPAWNED)
                )
            ) break
        }
    }

    suspend fun finishRun(subSessionId: String) {
        if (runDao.finishRun(subSessionId) != SubagentRunStatus.COMPLETED.name) return
        val parentId = chatSessionDao.getById(subSessionId)?.parentId ?: return
        eventBus.emit(SubAgentEvent(subSessionId, parentId, SubAgentEventType.COMPLETED))
    }

    suspend fun failRun(subSessionId: String, reason: () -> String) {
        runDao.markFailedPreservingFollowup(subSessionId, reason())
        val parentId = chatSessionDao.getById(subSessionId)?.parentId ?: return
        eventBus.emit(SubAgentEvent(subSessionId, parentId, SubAgentEventType.FAILED, reason()))
    }

    suspend fun markCancelled(subSessionId: String) {
        runDao.markCancelledIfRunning(subSessionId)
    }

    suspend fun markAllCancelled() {
        runDao.markAllPendingCancelled()
    }
}
