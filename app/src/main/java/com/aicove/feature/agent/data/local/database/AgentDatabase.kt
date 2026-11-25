package com.aicove.feature.agent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicove.feature.agent.data.local.dao.AgentMessageDao
import com.aicove.feature.agent.data.local.dao.ChatSessionDao
import com.aicove.feature.agent.data.local.dao.CheckpointDao
import com.aicove.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicove.feature.agent.data.local.dao.MemoryRecordDao
import com.aicove.feature.agent.data.local.dao.MemorySourceDao
import com.aicove.feature.agent.data.local.dao.MemoryVersionDao
import com.aicove.feature.agent.data.local.dao.SessionBriefDao
import com.aicove.feature.agent.data.local.dao.TaskStateDao
import com.aicove.feature.agent.data.local.dao.TodoItemDao
import com.aicove.feature.agent.data.local.entity.AgentMessageEntity
import com.aicove.feature.agent.data.local.entity.ChatSessionEntity
import com.aicove.feature.agent.data.local.entity.CheckpointEntity
import com.aicove.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aicove.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicove.feature.agent.data.local.entity.MemoryRecordEntity
import com.aicove.feature.agent.data.local.entity.MemorySourceEntity
import com.aicove.feature.agent.data.local.entity.MemoryVersionEntity
import com.aicove.feature.agent.data.local.entity.SessionBriefEntity
import com.aicove.feature.agent.data.local.entity.TaskEvidenceEntity
import com.aicove.feature.agent.data.local.entity.TaskStateEntity
import com.aicove.feature.agent.data.local.entity.TodoItemEntity
import com.aicove.feature.backup.data.local.dao.BackupTargetDao
import com.aicove.feature.backup.data.local.entity.BackupTargetEntity
import com.aicove.feature.settings.data.local.dao.AIProviderDao
import com.aicove.feature.settings.data.local.entity.AIProviderEntity
import com.aicove.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicove.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicove.feature.workspace.data.local.entity.RemoteMountEntity

@Database(
    entities = [AgentMessageEntity::class, ChatSessionEntity::class, AIProviderEntity::class, RemoteConnectionEntity::class, RemoteMountEntity::class, TodoItemEntity::class, SessionBriefEntity::class, CheckpointEntity::class, CheckpointFileSnapshotEntity::class, LlmCallRecordEntity::class, BackupTargetEntity::class, MemoryRecordEntity::class, MemorySourceEntity::class, MemoryVersionEntity::class, TaskStateEntity::class, TaskEvidenceEntity::class],
    version = AgentDatabase.SCHEMA_VERSION,
    exportSchema = true
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun aiProviderDao(): AIProviderDao
    abstract fun remoteConnectionDao(): RemoteConnectionDao
    abstract fun todoItemDao(): TodoItemDao
    abstract fun sessionBriefDao(): SessionBriefDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun llmCallRecordDao(): LlmCallRecordDao
    abstract fun memoryRecordDao(): MemoryRecordDao
    abstract fun memorySourceDao(): MemorySourceDao
    abstract fun memoryVersionDao(): MemoryVersionDao
    abstract fun taskStateDao(): TaskStateDao
    abstract fun backupTargetDao(): BackupTargetDao

    companion object {
        const val SCHEMA_VERSION = 72

        /** 数据库文件名（落在 `databases/` 下，另有 Room 默认 WAL 模式产生的 `-wal`/`-shm`）。 */
        const val DATABASE_NAME = "aicode_agent_db"

        fun memorySourceCallback() = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                installMemorySourceTriggers(db)
                installTaskEvidenceTriggers(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                installMemorySourceTriggers(db)
                installTaskEvidenceTriggers(db)
            }
        }

        fun installMemorySourceTriggers(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS memory_sources_agent_message_deleted
                BEFORE DELETE ON agent_messages
                BEGIN
                    UPDATE memory_sources
                    SET status = 'SOURCE_DELETED'
                    WHERE messageId = OLD.id;
                END
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS memory_sources_agent_message_content_changed
                AFTER UPDATE OF content ON agent_messages
                WHEN OLD.content IS NOT NEW.content
                BEGIN
                    UPDATE memory_sources
                    SET status = 'SOURCE_CHANGED'
                    WHERE messageId = OLD.id;
                END
                """.trimIndent()
            )
        }

        fun installTaskEvidenceTriggers(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS task_evidence_agent_message_deleted
                BEFORE DELETE ON agent_messages
                BEGIN
                    UPDATE task_evidence
                    SET sourceStatus = 'SOURCE_DELETED', verificationStatus = 'INVALIDATED'
                    WHERE sourceMessageId = OLD.id;
                END
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS task_evidence_agent_message_content_changed
                AFTER UPDATE OF content, toolArgs ON agent_messages
                WHEN OLD.content IS NOT NEW.content OR OLD.toolArgs IS NOT NEW.toolArgs
                BEGIN
                    UPDATE task_evidence
                    SET sourceStatus = 'SOURCE_CHANGED', verificationStatus = 'INVALIDATED'
                    WHERE sourceMessageId = OLD.id;
                END
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS task_evidence_source_reference_cleared
                AFTER UPDATE OF sourceMessageId ON task_evidence
                WHEN OLD.sourceMessageId IS NOT NULL AND NEW.sourceMessageId IS NULL
                     AND NEW.sourceStatus = 'SOURCE_CURRENT'
                BEGIN
                    UPDATE task_evidence
                    SET sourceStatus = 'SOURCE_DELETED', verificationStatus = 'INVALIDATED'
                    WHERE id = NEW.id;
                END
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS task_evidence_invalidate_task_completion
                AFTER UPDATE OF verificationStatus, sourceStatus ON task_evidence
                WHEN (OLD.verificationStatus = 'PASSED' AND NEW.verificationStatus != 'PASSED')
                     OR (OLD.sourceStatus = 'SOURCE_CURRENT' AND NEW.sourceStatus != 'SOURCE_CURRENT')
                BEGIN
                    UPDATE task_states
                    SET status = 'RECOVERY_REQUIRED', updatedAt = CAST(strftime('%s','now') AS INTEGER) * 1000
                    WHERE status = 'COMPLETED' AND id = NEW.taskId;
                END
                """.trimIndent()
            )
        }
    }
}
