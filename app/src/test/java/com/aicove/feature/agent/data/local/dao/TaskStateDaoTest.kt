package com.aicove.feature.agent.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicove.feature.agent.data.local.database.AgentDatabase
import com.aicove.feature.agent.data.local.entity.AgentMessageEntity
import com.aicove.feature.agent.data.local.entity.ChatSessionEntity
import com.aicove.feature.agent.data.local.entity.TaskEvidenceEntity
import com.aicove.feature.agent.data.local.entity.TaskEvidenceSourceStatus
import com.aicove.feature.agent.data.local.entity.TaskEvidenceType
import com.aicove.feature.agent.data.local.entity.TaskEvidenceVerificationStatus
import com.aicove.feature.agent.data.local.entity.TaskStateEntity
import com.aicove.feature.agent.data.local.entity.TaskStateStatus
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
class TaskStateDaoTest {
    private lateinit var database: AgentDatabase
    private lateinit var dao: TaskStateDao

    companion object {
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
        ).addCallback(AgentDatabase.memorySourceCallback()).build()
        dao = database.taskStateDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun only_host_recorded_successful_foreground_validation_can_pass_and_complete() = runBlocking {
        val task = task("/tmp/validation-project")
        dao.saveTask(task)
        val source = insertTool(task, message("validation-tool", "pytest: 12 passed"))

        // A transcript/tool observation is not host validation evidence.
        dao.addEvidence(evidence("ordinary-observation", task, source))
        assertFalse(dao.completeIfVerified(task.id, "ordinary-observation"))
        assertEquals(TaskStateStatus.PENDING.name, dao.getTaskById(task.id)?.status)

        // A real command must be a foreground validation command with exitCode=0, not cancelled,
        // not timed out, and its final source must still be the successful Bash TOOL row.
        assertFalse(dao.recordHostValidation(
            evidence("nonzero-validation", task, source, TaskEvidenceType.HOST_VALIDATION_PASS.name),
            command = "./gradlew testDebugUnitTest", exitCode = 1, cancelled = false, timedOut = false
        ))
        assertFalse(dao.recordHostValidation(
            evidence("cancelled-validation", task, source, TaskEvidenceType.HOST_VALIDATION_PASS.name),
            command = "pytest -q", exitCode = 0, cancelled = true, timedOut = false
        ))
        assertFalse(dao.recordHostValidation(
            evidence("timed-out-validation", task, source, TaskEvidenceType.HOST_VALIDATION_PASS.name),
            command = "npm test", exitCode = 0, cancelled = false, timedOut = true
        ))
        assertFalse(dao.recordHostValidation(
            evidence("not-validation", task, source, TaskEvidenceType.HOST_VALIDATION_PASS.name),
            command = "git status", exitCode = 0, cancelled = false, timedOut = false
        ))
        assertFalse(dao.recordHostValidation(
            evidence("command-mismatch", task, source, TaskEvidenceType.HOST_VALIDATION_PASS.name),
            command = "./gradlew check", exitCode = 0, cancelled = false, timedOut = false
        ))
        assertFalse(dao.recordHostValidation(
            evidence("chained-command", task, source, TaskEvidenceType.HOST_VALIDATION_PASS.name),
            command = "pytest -q; echo ok", exitCode = 0, cancelled = false, timedOut = false
        ))
        assertEquals(TaskStateStatus.PENDING.name, dao.getTaskById(task.id)?.status)

        val passedId = "host-validation-pass"
        assertTrue(dao.recordHostValidation(
            evidence(passedId, task, source, TaskEvidenceType.HOST_VALIDATION_PASS.name),
            command = "pytest -q", exitCode = 0, cancelled = false, timedOut = false
        ))
        assertEquals(TaskEvidenceVerificationStatus.PASSED.name, dao.getEvidence(passedId)?.verificationStatus)
        assertTrue(dao.completeIfVerified(task.id, passedId))
        assertEquals(TaskStateStatus.COMPLETED.name, dao.getTaskById(task.id)?.status)

        // Generic task/evidence writes cannot inject protected terminal/pass states.
        val completedSave = runCatching { dao.saveTask(task.copy(status = TaskStateStatus.COMPLETED.name)) }
        assertTrue(completedSave.exceptionOrNull() is IllegalArgumentException)
        val directPassed = evidence("direct-passed", task, source).copy(
            verificationStatus = TaskEvidenceVerificationStatus.PASSED.name
        )
        val passedSave = runCatching { dao.addEvidence(directPassed) }
        assertTrue(passedSave.exceptionOrNull() is IllegalArgumentException)
        assertEquals(source.content, database.agentMessageDao().getMessageById(source.id)?.content)
        assertEquals(source.id, dao.getEvidence(passedId)?.sourceMessageId)
        val columns = mutableSetOf<String>()
        database.openHelper.readableDatabase.query("PRAGMA table_info(`task_evidence`)").use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(1)
        }
        assertFalse("Evidence stores a reference and hash, never message body", "content" in columns)
    }

    @Test
    fun failed_tool_missing_source_and_wrong_project_cannot_complete() = runBlocking {
        val task = task("/tmp/task-one")
        dao.saveTask(task)
        val failed = insertTool(task, message("failed-tool", "validation failed", isError = true))
        assertFalse(dao.recordHostValidation(
            evidence("failed-tool-evidence", task, failed, TaskEvidenceType.HOST_VALIDATION_PASS.name),
            command = "./gradlew check", exitCode = 0, cancelled = false, timedOut = false
        ))

        val noSource = evidence("missing-source", task, failed, TaskEvidenceType.HOST_VALIDATION_PASS.name)
            .copy(sourceMessageId = null)
        assertFalse(dao.recordHostValidation(
            noSource,
            command = "npm test", exitCode = 0, cancelled = false, timedOut = false
        ))

        val otherProject = task("/tmp/task-two")
        dao.saveTask(otherProject)
        val wrongProjectSource = insertTool(otherProject, message("wrong-project-tool", "all tests passed"))
        assertFalse(dao.recordHostValidation(
            evidence("wrong-project-evidence", task, wrongProjectSource, TaskEvidenceType.HOST_VALIDATION_PASS.name),
            command = "pytest -q", exitCode = 0, cancelled = false, timedOut = false
        ))
        assertEquals(TaskStateStatus.PENDING.name, dao.getTaskById(task.id)?.status)
    }

    @Test
    fun editing_deleting_and_replacing_source_invalidates_completed_task() = runBlocking {
        val editedTask = task("/tmp/edit-project")
        dao.saveTask(editedTask)
        val editedSource = insertTool(editedTask, message("edited-tool", "all tests passed", command = "./gradlew check"))
        val editedEvidence = evidence("edited-evidence", editedTask, editedSource, TaskEvidenceType.HOST_VALIDATION_PASS.name)
        assertTrue(dao.recordHostValidation(editedEvidence, "./gradlew check", 0, false, false))
        assertTrue(dao.completeIfVerified(editedTask.id, editedEvidence.id))

        database.agentMessageDao().updateMessageContent(editedSource.id, "rewritten output")
        val changed = dao.getEvidence(editedEvidence.id)!!
        assertEquals(TaskEvidenceSourceStatus.SOURCE_CHANGED.name, changed.sourceStatus)
        assertEquals(TaskEvidenceVerificationStatus.INVALIDATED.name, changed.verificationStatus)
        assertEquals(TaskStateStatus.RECOVERY_REQUIRED.name, dao.getTaskById(editedTask.id)?.status)

        val deletedTask = task("/tmp/delete-project")
        dao.saveTask(deletedTask)
        val deletedSource = insertTool(deletedTask, message("deleted-tool", "all tests passed", command = "npm test"))
        val deletedEvidence = evidence("deleted-evidence", deletedTask, deletedSource, TaskEvidenceType.HOST_VALIDATION_PASS.name)
        assertTrue(dao.recordHostValidation(deletedEvidence, "npm test", 0, false, false))
        assertTrue(dao.completeIfVerified(deletedTask.id, deletedEvidence.id))
        database.agentMessageDao().deleteMessageById(deletedSource.id)
        val deleted = dao.getEvidence(deletedEvidence.id)!!
        assertNull(deleted.sourceMessageId)
        assertEquals(TaskEvidenceSourceStatus.SOURCE_DELETED.name, deleted.sourceStatus)
        assertEquals(TaskEvidenceVerificationStatus.INVALIDATED.name, deleted.verificationStatus)
        assertEquals(TaskStateStatus.RECOVERY_REQUIRED.name, dao.getTaskById(deletedTask.id)?.status)

        val replacedTask = task("/tmp/replace-project")
        dao.saveTask(replacedTask)
        val oldRow = insertTool(replacedTask, message("replace-tool", "all tests passed", command = "make test"))
        val replacedEvidence = evidence("replace-evidence", replacedTask, oldRow, TaskEvidenceType.HOST_VALIDATION_PASS.name)
        assertTrue(dao.recordHostValidation(replacedEvidence, "make test", 0, false, false))
        assertTrue(dao.completeIfVerified(replacedTask.id, replacedEvidence.id))
        // AgentMessageDao.insert uses REPLACE; verify delete+insert cannot leave evidence attached.
        database.agentMessageDao().insert(oldRow.copy(content = "[running] replacement placeholder"))
        val replaced = dao.getEvidence(replacedEvidence.id)!!
        assertNull(replaced.sourceMessageId)
        assertEquals(TaskEvidenceSourceStatus.SOURCE_DELETED.name, replaced.sourceStatus)
        assertEquals(TaskEvidenceVerificationStatus.INVALIDATED.name, replaced.verificationStatus)
        assertEquals(TaskStateStatus.RECOVERY_REQUIRED.name, dao.getTaskById(replacedTask.id)?.status)
    }

    @Test
    fun tasks_are_project_filtered_session_independent_and_allow_multiple_evidence() = runBlocking {
        val taskA = task("/tmp/a/../project", sessionId = "old-session")
        val taskB = task("/tmp/another-project", sessionId = "other-session")
        dao.saveTask(taskA)
        dao.saveTask(taskB)
        assertEquals(1, dao.updateStatus(taskA.id, TaskStateStatus.RUNNING.name, 11L, "new-session"))

        val tool1 = insertTool(taskA, message("project-tool-1", "first result", sessionId = "new-session"))
        val tool2 = insertTool(taskA, message("project-tool-2", "second result", sessionId = "new-session"))
        dao.addEvidence(evidence("project-evidence-1", taskA, tool1))
        dao.addEvidence(evidence("project-evidence-2", taskA, tool2))

        val filtered = dao.getUnfinishedTasks("/tmp/project/.")
        assertEquals(listOf(taskA.id), filtered.map { it.id })
        assertEquals("new-session", filtered.single().lastSessionId)
        assertEquals("/tmp/project", filtered.single().projectKey)
        assertEquals(2, dao.getEvidenceForTask(taskA.id).size)

        // Session deletion detaches evidence but never cascades the task row.
        database.agentMessageDao().deleteBySession("new-session")
        assertEquals(TaskStateStatus.RUNNING.name, dao.getTaskById(taskA.id)?.status)
        assertEquals("new-session", dao.getTaskById(taskA.id)?.lastSessionId)
        assertEquals(2, dao.getEvidenceForTask(taskA.id).size)
        assertTrue(dao.getEvidenceForTask(taskA.id).all { it.sourceMessageId == null })
    }

    private fun task(projectKey: String, sessionId: String? = "session-start") = TaskStateEntity(
        id = "task-${++taskIdCounter}",
        projectKey = projectKey,
        goal = "Verify durable task state",
        status = TaskStateStatus.PENDING.name,
        completedSteps = "[\"step a\"]",
        pendingSteps = "[\"step b\"]",
        blockedSteps = "[]",
        constraints = "[\"keep data\"]",
        lastSessionId = sessionId,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun message(
        id: String,
        content: String,
        sessionId: String = "task-session",
        isError: Boolean = false,
        command: String = "pytest -q"
    ) = AgentMessageEntity(
        id = id,
        sessionId = sessionId,
        role = "TOOL",
        content = content,
        timestamp = 10L,
        toolName = "Bash",
        toolArgs = "{\"command\":\"$command\"}",
        isError = isError
    )

    private suspend fun insertTool(task: TaskStateEntity, message: AgentMessageEntity): AgentMessageEntity {
        val session = ChatSessionEntity(
            id = message.sessionId,
            title = "task evidence session",
            createdAt = message.timestamp,
            updatedAt = message.timestamp,
            workspacePath = TaskProjectKey.normalize(task.projectKey)
        )
        database.chatSessionDao().upsert(session)
        database.agentMessageDao().insert(message)
        return message
    }

    private fun evidence(
        id: String,
        task: TaskStateEntity,
        source: AgentMessageEntity,
        type: String = TaskEvidenceType.TOOL_OBSERVATION.name
    ) = TaskEvidenceEntity(
        id = id,
        taskId = task.id,
        sourceMessageId = source.id,
        sourceSessionId = source.sessionId,
        evidenceType = type,
        verificationStatus = TaskEvidenceVerificationStatus.PENDING.name,
        sourceHash = TaskEvidenceHash.sha256(source.content, source.toolArgs),
        sourceStatus = TaskEvidenceSourceStatus.SOURCE_CURRENT.name,
        createdAt = 20L
    )

    private var taskIdCounter = 0
}
