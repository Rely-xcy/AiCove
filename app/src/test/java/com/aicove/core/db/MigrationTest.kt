package com.aicove.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicove.feature.agent.data.local.database.AgentDatabase
import com.aicove.feature.agent.data.local.entity.AgentMessageEntity
import com.aicove.feature.agent.data.local.entity.MemoryRecordEntity
import com.aicove.feature.agent.data.local.entity.MemorySourceHash
import com.aicove.feature.agent.data.local.entity.MemorySourceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Room 官方迁移测试（Robolectric，JVM 上跑真实的 Android SQLite）。
 *
 * 本容器（Android 宿主上的 PRoot）JVM 加载不了 Robolectric 的 conscrypt native 库
 * （UnsatisfiedLinkError，java.library.path 指向 /data/app、/system 等 Android 路径），
 * 故用 [guardEnvironment] 在非标准 Linux 环境跳过——跳过不算失败；
 * CI（ubuntu x86_64）与普通开发机上真实执行。
 *
 * 历史版本（8~49、51~52）的 schema json 当年未导出（app/schemas 下只有 50 与 53 起），
 * [MigrationTestHelper] 只能覆盖「当前版本及以后」的路径；历史路径的连续/无重复由
 * [MigrationFilesParseTest] 与 scripts/check_migrations.py 双向兜底。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue("Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）", !androidContainer)
        }
    }

    @Test
    fun open_current_schema_passes_identity_check() {
        helper.createDatabase(dbName, AgentDatabase.SCHEMA_VERSION).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        // 打开即触发 Room 的 identity/schema 校验，entity 与迁移产物不符会抛异常
        db.openHelper.writableDatabase
        db.close()
    }

    /**
     * 非正常降级会留下「user_version 落后于 migration_history」的库：旧版的
     * fallbackToDestructiveMigration 只 DROP Room 实体表再按旧 schema 重建（migration_history
     * 不在实体表之列因而保留），并把 user_version 置回旧值。升级时 Room 会把整条迁移链重放，
     * 历史表写入必须幂等，否则抛 SQLiteConstraintException:
     * UNIQUE constraint failed: migration_history.version。
     */
    @Test
    fun replaying_migrations_with_prerecorded_history_rows() {
        helper.createDatabase(dbName, 50).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS migration_history (" +
                        "version INTEGER PRIMARY KEY, script_name TEXT, executed_at INTEGER)"
            )
            for (version in 51..AgentDatabase.SCHEMA_VERSION) {
                execSQL(
                    "INSERT INTO migration_history (version, script_name, executed_at) " +
                            "VALUES ($version, 'prerecorded', 0)"
                )
            }
        }.close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        val columns = mutableSetOf<String>()
        db.openHelper.writableDatabase
            .query("PRAGMA table_info(`ai_providers`)")
            .use { cursor -> while (cursor.moveToNext()) columns += cursor.getString(1) }
        db.close()

        assertTrue(
            "重放迁移应补回 53/54 加入的两列，实际列：$columns",
            columns.containsAll(listOf("scriptParams", "customHeaders"))
        )
    }

    /**
     * 未来新增迁移（51+）时把 @AutoMigration/新迁移加进来后在此补升级用例：
     * createDatabase(50) → 跑迁移数组 → 断言新列/新表存在。
     */
    @Test
    fun migrate_58_to_59_adds_backup_targets() {
        helper.createDatabase(dbName, 58).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        val tables = mutableSetOf<String>()
        db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'")
            .use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        db.close()

        assertTrue(
            "迁移 59 应新增 backup_targets 表，实际表：$tables",
            tables.contains("backup_targets")
        )
    }

    @Test
    fun migrate_70_to_71_adds_memory_tables_and_message_lineage_triggers() = runBlocking {
        helper.createDatabase(dbName, 70).close()

        val database = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .addCallback(AgentDatabase.memorySourceCallback())
            .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            val tables = mutableSetOf<String>()
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue(tables.containsAll(listOf("memory_records", "memory_sources", "memory_versions")))

            val sourceForeignKeys = mutableSetOf<Pair<String, String>>()
            val versionLinkColumns = mutableSetOf<Pair<String, String>>()
            sqlite.query("PRAGMA foreign_key_list(`memory_sources`)").use { cursor ->
                while (cursor.moveToNext()) {
                    val table = cursor.getString(2)
                    sourceForeignKeys += table to cursor.getString(6)
                    if (table == "memory_versions") {
                        versionLinkColumns += cursor.getString(3) to cursor.getString(4)
                    }
                }
            }
            assertTrue(
                "memory_sources 应保留 memory/version，消息删除应 SET NULL：$sourceForeignKeys",
                sourceForeignKeys.containsAll(
                    setOf(
                        "memory_records" to "CASCADE",
                        "memory_versions" to "CASCADE",
                        "agent_messages" to "SET NULL"
                    )
                )
            )
            assertEquals(setOf("memoryId" to "memoryId", "versionId" to "id"), versionLinkColumns)

            val triggers = mutableSetOf<String>()
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { cursor ->
                while (cursor.moveToNext()) triggers += cursor.getString(0)
            }
            assertTrue("删除来源消息的触发器缺失：$triggers", "memory_sources_agent_message_deleted" in triggers)
            assertTrue("编辑来源消息的触发器缺失：$triggers", "memory_sources_agent_message_content_changed" in triggers)

            val message = AgentMessageEntity(
                id = "migration-source-message",
                sessionId = "migration-source-session",
                role = "USER",
                content = "source content",
                timestamp = 1L
            )
            val record = MemoryRecordEntity(
                id = "migration-memory",
                markdownPath = null,
                name = "migration-memory",
                description = "migration test",
                scope = "GLOBAL",
                kind = "PROFILE",
                source = "auto-distill",
                createdAt = 1L,
                updatedAt = 1L,
                hitCount = 0,
                lastHitAt = 0L,
                pinned = false
            )
            database.agentMessageDao().insert(message)
            database.memoryRecordDao().saveVersion(
                record = record,
                content = "independent memory content",
                sources = listOf(
                    MemorySourceInput(
                        messageId = message.id,
                        evidenceType = "transcript",
                        position = 0,
                        messageHash = MemorySourceHash.sha256(message.content)
                    )
                ),
                reason = "migration-test",
                createdAt = 2L
            )

            database.agentMessageDao().updateMessageContent(message.id, "edited source")
            assertEquals(
                "SOURCE_CHANGED",
                database.memoryRecordDao().getCurrentSources(record.id).single().status
            )

            database.agentMessageDao().deleteMessageById(message.id)
            val source = database.memoryRecordDao().getCurrentSources(record.id).single()
            assertNull(source.messageId)
            assertEquals("SOURCE_DELETED", source.status)
            assertEquals("independent memory content", database.memoryRecordDao().getVersionHistory(record.id).single().content)
            assertNotNull(database.memoryRecordDao().getRecordById(record.id))

            val deletedIds = mutableSetOf<String>()
            sqlite.query("SELECT messageId FROM memory_sources WHERE messageId IS NOT NULL").use { cursor ->
                while (cursor.moveToNext()) deletedIds += cursor.getString(0)
            }
            assertTrue("删除后的来源 ID 不得在历史版本来源里残留：$deletedIds", message.id !in deletedIds)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate_71_to_72_adds_task_state_evidence_schema_and_lifecycle_triggers() {
        helper.createDatabase(dbName, 71).close()

        val database = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .addCallback(AgentDatabase.memorySourceCallback())
            .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            val tables = mutableSetOf<String>()
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue(tables.containsAll(listOf("task_states", "task_evidence")))

            val taskIndexes = mutableSetOf<String>()
            sqlite.query("PRAGMA index_list(`task_states`)").use { cursor ->
                while (cursor.moveToNext()) taskIndexes += cursor.getString(1)
            }
            val evidenceIndexes = mutableSetOf<String>()
            sqlite.query("PRAGMA index_list(`task_evidence`)").use { cursor ->
                while (cursor.moveToNext()) evidenceIndexes += cursor.getString(1)
            }
            assertTrue("task state project/status/update index missing: $taskIndexes", "index_task_states_projectKey_status_updatedAt" in taskIndexes)
            assertTrue("task evidence task/status index missing: $evidenceIndexes", "index_task_evidence_taskId_verificationStatus" in evidenceIndexes)
            assertTrue("task evidence source message index missing: $evidenceIndexes", "index_task_evidence_sourceMessageId" in evidenceIndexes)

            val evidenceForeignKeys = mutableSetOf<Pair<String, String>>()
            sqlite.query("PRAGMA foreign_key_list(`task_evidence`)").use { cursor ->
                while (cursor.moveToNext()) evidenceForeignKeys += cursor.getString(2) to cursor.getString(6)
            }
            assertTrue(
                "task evidence must cascade with task and null its source message: $evidenceForeignKeys",
                evidenceForeignKeys.containsAll(setOf("task_states" to "CASCADE", "agent_messages" to "SET NULL"))
            )

            val triggers = mutableSetOf<String>()
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { cursor ->
                while (cursor.moveToNext()) triggers += cursor.getString(0)
            }
            assertTrue("source delete trigger missing: $triggers", "task_evidence_agent_message_deleted" in triggers)
            assertTrue("source edit trigger missing: $triggers", "task_evidence_agent_message_content_changed" in triggers)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate_72_to_73_adds_subagent_run_state_and_cascade_keys() {
        // 72.json is not exported in the source tree on this baseline. Build the valid v72 fixture
        // by migrating its exported v71 database with the production file migration, then exercise
        // the ordinary Room 72 -> 73 path below.
        val v71 = helper.createDatabase(dbName, 71)
        val migration72 = MigrationLoader.loadMigrations(context)
            .filterIsInstance<FileMigration>()
            .single { it.version == 72 }
        migration72.migrate(v71)
        v71.execSQL("PRAGMA user_version = 72")
        v71.close()

        val database = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            val columns = mutableSetOf<String>()
            sqlite.query("PRAGMA table_info(`subagent_runs`)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(1)
            }
            assertEquals(
                setOf("subSessionId", "parentSessionId", "status", "startedAt", "updatedAt", "terminationReason", "claimedPrompt", "initialPrompt"),
                columns
            )

            val foreignKeys = mutableSetOf<Triple<String, String, String>>()
            sqlite.query("PRAGMA foreign_key_list(`subagent_runs`)").use { cursor ->
                while (cursor.moveToNext()) {
                    foreignKeys += Triple(cursor.getString(3), cursor.getString(2), cursor.getString(6))
                }
            }
            assertEquals(
                setOf(
                    Triple("subSessionId", "chat_sessions", "CASCADE"),
                    Triple("parentSessionId", "chat_sessions", "CASCADE")
                ),
                foreignKeys
            )
            val indexes = mutableSetOf<String>()
            sqlite.query("PRAGMA index_list(`subagent_runs`)").use { cursor ->
                while (cursor.moveToNext()) indexes += cursor.getString(1)
            }
            assertTrue("parent/status index missing: $indexes", "index_subagent_runs_parentSessionId_status" in indexes)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate_69_to_70_adds_message_session_timestamp_index() {
        helper.createDatabase(dbName, 69).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        val indexes = mutableSetOf<String>()
        val sqlite = db.openHelper.writableDatabase
        sqlite.query("SELECT name FROM sqlite_master WHERE type='index'")
            .use { cursor -> while (cursor.moveToNext()) indexes += cursor.getString(0) }
        val queryPlan = buildString {
            sqlite.query(
                "EXPLAIN QUERY PLAN SELECT * FROM (SELECT * FROM agent_messages " +
                    "WHERE sessionId = 'session' ORDER BY timestamp DESC LIMIT 30) ORDER BY timestamp ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) append(cursor.getString(3)).append('\n')
            }
        }
        db.close()

        assertTrue(
            "迁移 70 应新增 agent_messages(sessionId, timestamp) 索引，实际索引：$indexes",
            "index_agent_messages_sessionId_timestamp" in indexes
        )
        assertTrue("旧单列索引应由复合索引取代，实际索引：$indexes", "index_agent_messages_sessionId" !in indexes)
        assertTrue("分页排序查询应使用复合索引，实际查询计划：$queryPlan", queryPlan.contains("index_agent_messages_sessionId_timestamp"))
    }

    @Test
    fun migrate_65_to_66_adds_session_briefs() {
        helper.createDatabase(dbName, 65).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        val tables = mutableSetOf<String>()
        db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'")
            .use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        db.close()

        assertTrue(
            "迁移 66 应新增 session_briefs 表，实际表：$tables",
            tables.contains("session_briefs")
        )
    }

    @Test
    fun migrate_72_through_73_to_74_adds_agent_turn_runs_schema() {
        // 72.json is not exported. Build an exact v72 database from the exported v71 schema
        // plus the production 72 migration, then let Room run the ordinary 73 -> 74 path.
        val v71 = helper.createDatabase(dbName, 71)
        val migration72 = MigrationLoader.loadMigrations(context)
            .filterIsInstance<FileMigration>()
            .single { it.version == 72 }
        migration72.migrate(v71)
        v71.execSQL("PRAGMA user_version = 72")
        v71.close()

        val database = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            val columns = mutableSetOf<String>()
            val primaryKeyColumns = mutableMapOf<String, Int>()
            sqlite.query("PRAGMA table_info(`agent_turn_runs`)").use { cursor ->
                while (cursor.moveToNext()) {
                    val column = cursor.getString(1)
                    columns += column
                    val primaryKeyPosition = cursor.getInt(5)
                    if (primaryKeyPosition != 0) primaryKeyColumns[column] = primaryKeyPosition
                }
            }
            assertEquals(
                setOf("sessionId", "userMessageId", "status", "startedAt", "updatedAt"),
                columns
            )
            assertEquals(mapOf("sessionId" to 1), primaryKeyColumns)

            val uniqueIndexes = mutableSetOf<String>()
            sqlite.query("PRAGMA index_list(`agent_turn_runs`)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getInt(2) != 0) uniqueIndexes += cursor.getString(1)
                }
            }
            assertTrue(
                "agent_turn_runs.userMessageId unique index missing: $uniqueIndexes",
                "index_agent_turn_runs_userMessageId" in uniqueIndexes
            )
            val uniqueIndexColumns = mutableListOf<String>()
            sqlite.query("PRAGMA index_info(`index_agent_turn_runs_userMessageId`)").use { cursor ->
                while (cursor.moveToNext()) uniqueIndexColumns += cursor.getString(2)
            }
            assertEquals(listOf("userMessageId"), uniqueIndexColumns)

            val foreignKeys = mutableSetOf<List<String>>()
            sqlite.query("PRAGMA foreign_key_list(`agent_turn_runs`)").use { cursor ->
                while (cursor.moveToNext()) {
                    foreignKeys += listOf(
                        cursor.getString(3),
                        cursor.getString(2),
                        cursor.getString(4),
                        cursor.getString(6)
                    )
                }
            }
            assertEquals(
                setOf(
                    listOf("sessionId", "chat_sessions", "id", "CASCADE"),
                    listOf("userMessageId", "agent_messages", "id", "CASCADE")
                ),
                foreignKeys
            )

            val appliedVersions = mutableSetOf<Int>()
            sqlite.query("SELECT version FROM migration_history WHERE version IN (73, 74)").use { cursor ->
                while (cursor.moveToNext()) appliedVersions += cursor.getInt(0)
            }
            assertEquals(setOf(73, 74), appliedVersions)
            var userVersion = 0
            sqlite.query("PRAGMA user_version").use { cursor ->
                if (cursor.moveToFirst()) userVersion = cursor.getInt(0)
            }
            assertEquals(AgentDatabase.SCHEMA_VERSION, userVersion)
        } finally {
            database.close()
        }
    }
}