package com.aicove.feature.agent.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicove.feature.agent.data.local.database.AgentDatabase
import com.aicove.feature.agent.data.local.entity.ChatSessionEntity
import com.aicove.feature.agent.data.local.entity.SubagentRunEntity
import com.aicove.feature.agent.data.local.entity.SubagentRunStatus
import com.aicove.feature.agent.data.local.entity.SubagentRunTerminationReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SubagentRunDaoTest {
    private lateinit var database: AgentDatabase
    private lateinit var sessions: ChatSessionDao
    private lateinit var runs: SubagentRunDao

    companion object {
        private const val PARENT = "parent-session"

        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）", !androidContainer)
        }
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AgentDatabase::class.java
        ).build()
        sessions = database.chatSessionDao()
        runs = database.subagentRunDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun lifecycleTransitions_queueRunCompleteFailAndCancel() = runBlocking {
        sessions.upsert(session(PARENT))
        val child = session("child-1", PARENT)
        runs.createQueuedRun(child, run(child.id, SubagentRunStatus.QUEUED, prompt = "first prompt"))

        assertEquals(SubagentRunStatus.QUEUED.name, runs.getBySubSessionId(child.id)?.status)
        assertEquals("first prompt", runs.claimQueuedPrompt(child.id, 10L))
        assertNull("a second consumer cannot replay a claimed prompt", runs.claimQueuedPrompt(child.id, 11L))
        val running = runs.getBySubSessionId(child.id)!!
        assertEquals(SubagentRunStatus.RUNNING.name, running.status)
        assertNull("claimed prompt is separate from the pending continuation slot", running.initialPrompt)
        assertEquals("first prompt", running.claimedPrompt)
        assertEquals(1, runs.clearClaimedPrompt(child.id, "first prompt", 12L))
        assertNull(runs.getBySubSessionId(child.id)?.claimedPrompt)
        assertEquals(SubagentRunStatus.COMPLETED.name, runs.finishRun(child.id, 20L))
        assertEquals(SubagentRunStatus.COMPLETED.name, runs.getBySubSessionId(child.id)?.status)

        runs.queueContinuation(child.id, PARENT, "explicit retry only", 30L)
        assertEquals("explicit retry only", runs.getBySubSessionId(child.id)?.initialPrompt)
        assertEquals("explicit retry only", runs.claimQueuedPrompt(child.id, 31L))
        assertEquals(1, runs.markFailed(child.id, "provider error", 40L))
        val failed = runs.getBySubSessionId(child.id)!!
        assertEquals(SubagentRunStatus.FAILED.name, failed.status)
        assertEquals("provider error", failed.terminationReason)

        runs.queueContinuation(child.id, PARENT, "another explicit turn", 50L)
        assertEquals("another explicit turn", runs.claimQueuedPrompt(child.id, 51L))
        assertEquals(1, runs.markCancelled(child.id, SubagentRunTerminationReason.STOPPED, 60L))
        val cancelled = runs.getBySubSessionId(child.id)!!
        assertEquals(SubagentRunStatus.CANCELLED.name, cancelled.status)
        assertEquals(SubagentRunTerminationReason.STOPPED, cancelled.terminationReason)

        val pendingChild = session("pending-stop", PARENT)
        runs.createQueuedRun(pendingChild, run(pendingChild.id, SubagentRunStatus.QUEUED, prompt = "queued work"))
        assertEquals(1, runs.markAllPendingCancelled(70L))
        val stoppedPending = runs.getBySubSessionId(pendingChild.id)!!
        assertEquals(SubagentRunStatus.CANCELLED.name, stoppedPending.status)
        assertEquals(SubagentRunTerminationReason.STOPPED, stoppedPending.terminationReason)
        assertNull("cancelled work must not retain a replayable prompt", stoppedPending.initialPrompt)
    }

    @Test
    fun runningSendQueuesDurablyAndNormalFinishTransitionsToQueuedExactlyOnce() = runBlocking {
        sessions.upsert(session(PARENT))
        val child = session("child-running-send", PARENT)
        runs.createQueuedRun(child, run(child.id, SubagentRunStatus.QUEUED, prompt = "original turn"))
        assertEquals("original turn", runs.claimQueuedPrompt(child.id, 10L))

        assertEquals(
            SubagentRunStatus.RUNNING.name,
            runs.queueContinuation(child.id, PARENT, "follow-up while running", 11L)
        )
        val duringCurrentTurn = runs.getBySubSessionId(child.id)!!
        assertEquals(SubagentRunStatus.RUNNING.name, duringCurrentTurn.status)
        assertEquals("original turn", duringCurrentTurn.claimedPrompt)
        assertEquals("follow-up while running", duringCurrentTurn.initialPrompt)
        assertEquals(1, runs.clearClaimedPrompt(child.id, "original turn", 12L))
        val afterCurrentInputPersisted = runs.getBySubSessionId(child.id)!!
        assertNull(afterCurrentInputPersisted.claimedPrompt)
        assertEquals("clearing current input must preserve queued continuation", "follow-up while running", afterCurrentInputPersisted.initialPrompt)

        assertEquals(SubagentRunStatus.QUEUED.name, runs.finishRun(child.id, 20L))
        val queuedNextTurn = runs.getBySubSessionId(child.id)!!
        assertEquals(SubagentRunStatus.QUEUED.name, queuedNextTurn.status)
        assertNull(queuedNextTurn.claimedPrompt)
        assertEquals("follow-up while running", queuedNextTurn.initialPrompt)
        assertEquals("follow-up while running", runs.claimQueuedPrompt(child.id, 21L))
        assertNull("only one consumer can claim the continuation", runs.claimQueuedPrompt(child.id, 22L))
        assertEquals(1, runs.clearClaimedPrompt(child.id, "follow-up while running", 23L))
        assertEquals(SubagentRunStatus.COMPLETED.name, runs.finishRun(child.id, 30L))

        val finishFirstChild = session("finish-first-child", PARENT)
        runs.createQueuedRun(finishFirstChild, run(finishFirstChild.id, SubagentRunStatus.QUEUED, prompt = "current"))
        assertEquals("current", runs.claimQueuedPrompt(finishFirstChild.id, 31L))
        assertEquals(1, runs.clearClaimedPrompt(finishFirstChild.id, "current", 32L))
        assertEquals(SubagentRunStatus.COMPLETED.name, runs.finishRun(finishFirstChild.id, 33L))
        assertEquals(
            SubagentRunStatus.QUEUED.name,
            runs.queueContinuation(finishFirstChild.id, PARENT, "sent just after finish", 34L)
        )
        assertEquals("sent just after finish", runs.getBySubSessionId(finishFirstChild.id)?.initialPrompt)
    }

    @Test
    fun crashBeforeFirstUserMessagePersistedInterruptsCurrentAndDropsDependentQueuedSend() = runBlocking {
        sessions.upsert(session(PARENT))
        val child = session("child-crash-before-persist", PARENT)
        runs.createQueuedRun(child, run(child.id, SubagentRunStatus.QUEUED, prompt = "original prompt"))
        assertEquals("original prompt", runs.claimQueuedPrompt(child.id, 10L))
        assertEquals(
            SubagentRunStatus.RUNNING.name,
            runs.queueContinuation(child.id, PARENT, "follow-up depends on current run", 11L)
        )
        val beforeCrash = runs.getBySubSessionId(child.id)!!
        assertEquals("original prompt", beforeCrash.claimedPrompt)
        assertEquals("follow-up depends on current run", beforeCrash.initialPrompt)

        runs.interruptRunningRuns(100L)

        val interrupted = runs.getBySubSessionId(child.id)!!
        assertEquals(SubagentRunStatus.INTERRUPTED.name, interrupted.status)
        assertEquals(SubagentRunTerminationReason.RECOVERY_REQUIRED, interrupted.terminationReason)
        assertEquals("unpersisted current prompt remains identifiable but is not runnable", "original prompt", interrupted.claimedPrompt)
        assertEquals("dependent follow-up remains identifiable but is not runnable", "follow-up depends on current run", interrupted.initialPrompt)
        assertNull("INTERRUPTED rows are never claimable", runs.claimQueuedPrompt(child.id, 101L))
        assertTrue(runs.getQueuedRuns().isEmpty())
    }

    @Test
    fun failedRunPreservesFollowUpUntilExplicitSendThenQueuesOnlyPendingInput() = runBlocking {
        sessions.upsert(session(PARENT))
        val child = session("child-failed-with-pending", PARENT)
        runs.createQueuedRun(child, run(child.id, SubagentRunStatus.QUEUED, prompt = "failed current turn"))
        assertEquals("failed current turn", runs.claimQueuedPrompt(child.id, 10L))
        runs.queueContinuation(child.id, PARENT, "follow-up already sent by parent", 11L)

        assertEquals(1, runs.markFailedPreservingFollowup(child.id, "provider error", 20L))
        val failed = runs.getBySubSessionId(child.id)!!
        assertEquals(SubagentRunStatus.FAILED.name, failed.status)
        assertEquals("provider error", failed.terminationReason)
        assertNull("failed current turn is never replayed", failed.claimedPrompt)
        assertEquals("follow-up already sent by parent", failed.initialPrompt)
        assertTrue("FAILED follow-up is not automatically runnable", runs.getQueuedRuns().none { it.subSessionId == child.id })

        val resumed = runs.resumeFailedRunWithExplicitMessage(
            child.id,
            PARENT,
            "resume from the failure point",
            30L
        )
        assertEquals(SubagentRunStatus.QUEUED.name, resumed)
        val queued = runs.getBySubSessionId(child.id)!!
        assertEquals(SubagentRunStatus.QUEUED.name, queued.status)
        assertEquals(
            "follow-up already sent by parent\\n\\n[Additional message]\\nresume from the failure point",
            queued.initialPrompt
        )
        assertEquals(queued.initialPrompt, runs.claimQueuedPrompt(child.id, 31L))
        assertNull("the explicit recovery input is claimed only once", runs.claimQueuedPrompt(child.id, 32L))
    }

    @Test
    fun failedCurrentRunPreservesFollowUpUntilExplicitRecoverySend() = runBlocking {
        sessions.upsert(session(PARENT))
        val child = session("failed-followup", PARENT)
        runs.createQueuedRun(child, run(child.id, SubagentRunStatus.QUEUED, prompt = "failed current task"))
        assertEquals("failed current task", runs.claimQueuedPrompt(child.id, 10L))
        runs.queueContinuation(child.id, PARENT, "already-sent follow-up", 11L)

        assertEquals(1, runs.markFailedPreservingFollowup(child.id, "provider error", 20L))
        val failed = runs.getBySubSessionId(child.id)!!
        assertEquals(SubagentRunStatus.FAILED.name, failed.status)
        assertEquals("provider error", failed.terminationReason)
        assertNull("failed turn input must not be replayed", failed.claimedPrompt)
        assertEquals("already-sent follow-up", failed.initialPrompt)
        assertTrue("preserved follow-up is not automatically queued", runs.getQueuedRuns().none { it.subSessionId == child.id })

        val resumed = runs.resumeFailedRunWithExplicitMessage(
            child.id,
            PARENT,
            "continue after fixing the provider",
            22L
        )
        assertEquals(SubagentRunStatus.QUEUED.name, resumed)
        val queued = runs.getBySubSessionId(child.id)!!
        assertEquals(
            "already-sent follow-up\n\n[Additional message]\ncontinue after fixing the provider",
            queued.initialPrompt
        )
        assertNull(queued.claimedPrompt)
        assertEquals(queued.initialPrompt, runs.claimQueuedPrompt(child.id, 23L))
        assertNull(runs.claimQueuedPrompt(child.id, 24L))
    }

    @Test
    fun stoppingFailedOrInterruptedRunClearsPreservedContinuationWithoutRequeue() = runBlocking {
        sessions.upsert(session(PARENT))
        val failed = session("failed-to-stop", PARENT)
        val interrupted = session("interrupted-to-stop", PARENT)
        sessions.upsert(failed)
        sessions.upsert(interrupted)
        runs.upsertRun(run(failed.id, SubagentRunStatus.FAILED, prompt = "pending after failure"))
        runs.upsertRun(run(interrupted.id, SubagentRunStatus.INTERRUPTED, prompt = "pending after interruption"))

        assertEquals(1, runs.markCancelled(failed.id, SubagentRunTerminationReason.STOPPED, 10L))
        assertEquals(1, runs.markCancelled(interrupted.id, SubagentRunTerminationReason.STOPPED, 11L))

        val stoppedFailed = runs.getBySubSessionId(failed.id)!!
        val stoppedInterrupted = runs.getBySubSessionId(interrupted.id)!!
        assertEquals(SubagentRunStatus.CANCELLED.name, stoppedFailed.status)
        assertEquals(SubagentRunStatus.CANCELLED.name, stoppedInterrupted.status)
        assertNull(stoppedFailed.initialPrompt)
        assertNull(stoppedInterrupted.initialPrompt)
        assertTrue("stopped follow-ups must not be auto-queued", runs.getQueuedRuns().isEmpty())

        assertEquals(
            SubagentRunStatus.QUEUED.name,
            runs.resumeFailedRunWithExplicitMessage(failed.id, PARENT, "brand new user instruction", 12L)
        )
        val afterExplicitSend = runs.getBySubSessionId(failed.id)!!
        assertEquals("brand new user instruction", afterExplicitSend.initialPrompt)
        assertFalse("stop must discard the previous pending instruction", afterExplicitSend.initialPrompt!!.contains("pending after failure"))
    }

    @Test
    fun coldStartKeepsQueuedPromptAndMarksRunningInterruptedWithoutReplay() = runBlocking {
        sessions.upsert(session(PARENT))
        val queued = session("queued-child", PARENT)
        val running = session("running-child", PARENT)
        runs.createQueuedRun(queued, run(queued.id, SubagentRunStatus.QUEUED, prompt = "safe to start"))
        runs.createQueuedRun(running, run(running.id, SubagentRunStatus.QUEUED, prompt = "already started"))
        assertEquals("already started", runs.claimQueuedPrompt(running.id, 10L))
        assertEquals(
            SubagentRunStatus.RUNNING.name,
            runs.queueContinuation(running.id, PARENT, "actual pending follow-up", 11L)
        )

        runs.interruptRunningRuns(100L)

        val queuedAfterRestart = runs.getBySubSessionId(queued.id)!!
        assertEquals(SubagentRunStatus.QUEUED.name, queuedAfterRestart.status)
        assertEquals("safe to start", queuedAfterRestart.initialPrompt)
        val interrupted = runs.getBySubSessionId(running.id)!!
        assertEquals(SubagentRunStatus.INTERRUPTED.name, interrupted.status)
        assertEquals(SubagentRunTerminationReason.RECOVERY_REQUIRED, interrupted.terminationReason)
        assertEquals("claimed input is recorded for diagnosis but not automatically replayed", "already started", interrupted.claimedPrompt)
        assertEquals("actual pending follow-up", interrupted.initialPrompt)
        assertEquals(listOf(queued.id), runs.getQueuedRuns().map { it.subSessionId })

        assertEquals(
            SubagentRunStatus.INTERRUPTED.name,
            runs.queueContinuation(running.id, PARENT, "unconfirmed new instruction", 200L)
        )
        assertEquals("actual pending follow-up", runs.getBySubSessionId(running.id)?.initialPrompt)
        val resumedState = runs.resumeFailedRunWithExplicitMessage(
            running.id,
            PARENT,
            "new explicit continuation",
            201L
        )
        assertEquals(SubagentRunStatus.QUEUED.name, resumedState)
        val continuation = runs.getBySubSessionId(running.id)!!
        assertEquals(SubagentRunStatus.QUEUED.name, continuation.status)
        assertNull("current failed prompt is not restored as runnable", continuation.claimedPrompt)
        assertEquals(
            "actual pending follow-up\n\n[Additional message]\nnew explicit continuation",
            continuation.initialPrompt
        )
        val noPending = session("interrupted-no-pending", PARENT)
        runs.createQueuedRun(noPending, run(noPending.id, SubagentRunStatus.QUEUED, prompt = "failed current only"))
        assertEquals("failed current only", runs.claimQueuedPrompt(noPending.id, 300L))
        runs.interruptRunningRun(noPending.id, 301L)
        val explicitOnly = runs.resumeFailedRunWithExplicitMessage(
            noPending.id,
            PARENT,
            "new explicit instruction only",
            302L
        )
        assertEquals(SubagentRunStatus.QUEUED.name, explicitOnly)
        val noPendingQueued = runs.getBySubSessionId(noPending.id)!!
        assertEquals("new explicit instruction only", noPendingQueued.initialPrompt)
        assertTrue(!noPendingQueued.initialPrompt!!.contains("failed current only"))
        assertEquals("new explicit instruction only", runs.claimQueuedPrompt(noPending.id, 303L))
    }

    @Test
    fun deletingChildOrParentCascadesRunRows() = runBlocking {
        sessions.upsert(session(PARENT))
        val child = session("child-delete", PARENT)
        sessions.upsert(child)
        runs.upsertRun(run(child.id, SubagentRunStatus.QUEUED, prompt = "pending"))

        sessions.delete(child.id)
        assertNull(runs.getBySubSessionId(child.id))

        val secondChild = session("child-parent-delete", PARENT)
        sessions.upsert(secondChild)
        runs.upsertRun(run(secondChild.id, SubagentRunStatus.RUNNING))
        sessions.delete(PARENT)
        assertNull(runs.getBySubSessionId(secondChild.id))
        assertTrue("parent deletion must not leave dangling run rows", runs.getByParentSessionId(PARENT).isEmpty())
    }

    private fun session(id: String, parentId: String? = null) = ChatSessionEntity(
        id = id,
        title = id,
        createdAt = 1L,
        updatedAt = 1L,
        parentId = parentId
    )

    private fun run(id: String, status: SubagentRunStatus, prompt: String? = null) = SubagentRunEntity(
        subSessionId = id,
        parentSessionId = PARENT,
        status = status.name,
        startedAt = 1L,
        updatedAt = 1L,
        initialPrompt = prompt
    )
}
