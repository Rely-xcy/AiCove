package com.aicove.feature.workspace.data.repository

import android.content.Context
import com.aicove.feature.agent.domain.container.ConnectionState
import com.aicove.feature.agent.domain.container.RemoteConnectionConfig
import com.aicove.feature.agent.domain.container.RemoteSshConnection
import com.aicove.feature.agent.domain.session.SessionUseCase
import com.aicove.feature.credentials.data.CredentialInjectSettingsRepository
import com.aicove.feature.credentials.domain.repository.CredentialRepository
import com.aicove.feature.settings.data.repository.ExecutionMode
import com.aicove.feature.settings.data.repository.ExecutionModeHolder
import com.aicove.feature.settings.data.repository.GeneralSettingsRepository
import com.aicove.feature.workspace.domain.PathHomeResolver
import com.aicove.feature.workspace.domain.remote.RemoteAuth
import com.aicove.feature.workspace.domain.remote.agent.RemoteAgentHolder
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * deleteWorkspace 回归：删除工作区后不再级联删除其会话与消息。
 *
 * 旧行为：deleteWorkspace 在本地内部与远程两个分支里都调用
 * `target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }`，把绑定到该工作区的
 * 全部根会话与子会话连同消息一并清掉。
 * 新行为：只删目录（本地）或发 `rm -rf`（远程），会话记录保留在数据库中。
 *
 * 关键点：两条用例都必须让 `_workspaces` 里**真实存在**目标工作区，否则旧实现的
 * `target?.let { ... }` 因 target 为 null 根本不触发，测试就退化成「两边都绿」，钉不住回归。
 * 因此本地用例先在 projectsRoot 下建出 projA 再 initialize()；远程用例桩好远端列目录与
 * `rm -rf` 的 exec 返回，让 initialize() 真正把 default 装进工作区列表。
 *
 * 除会话不被删外，同时验证删除的副作用真实发生（本地目录内容消失、远程工作区列表移除该项）。
 */
class WorkspaceRepositoryDeleteWorkspaceSessionPreservationTest {

    private val context = mockk<Context>(relaxed = true)
    private val executionModeHolder = mockk<ExecutionModeHolder>()
    private val remoteSshConnection = mockk<RemoteSshConnection>(relaxed = true)
    private val pathHomeResolver = mockk<PathHomeResolver>()
    private val sessionUseCase = mockk<SessionUseCase>()
    private val generalSettingsRepository = mockk<GeneralSettingsRepository>()

    /**
     * DataStore 与 projectsRoot 共用一份真实临时目录。
     * `preferencesDataStore` 委托在 JVM 内是单例、缓存首次传入的 Context，所以目录放在
     * companion 里全类共用且只创建不删除——放成实例字段会让第二个用例拿到另一个目录，
     * 与已缓存的 DataStore 不一致。
     */
    private companion object {
        val filesDir: File by lazy { Files.createTempDirectory("ws-delete-preserve-test").toFile() }
    }

    /** 本地内部工作区根（与仓库内部的 projectsRoot 同构：filesDir/projects）。 */
    private val projectsRoot: File get() = File(filesDir, "projects")

    /** 远程工作区根（remoteWorkspacePath 展开后）。 */
    private val remoteRoot = "/srv/workspace"

    /** 远程 exec 收到过的命令，按顺序记录，用于断言删除命令确实发出。 */
    private val remoteCommands = mutableListOf<String>()

    /** 远端 default 目录是否已被删除：控制列目录返回空，模拟删除后的刷新。 */
    private var remoteDeleted = false

    private fun newRepo(): WorkspaceRepository = WorkspaceRepository(
        context = context,
        executionModeHolder = executionModeHolder,
        remoteSshConnection = remoteSshConnection,
        pathHomeResolver = pathHomeResolver,
        sessionUseCase = sessionUseCase,
        generalSettingsRepository = generalSettingsRepository,
        credentialInjectSettings = mockk<CredentialInjectSettingsRepository>(relaxed = true),
        credentialRepository = mockk<CredentialRepository>(relaxed = true),
        agentHolder = mockk<RemoteAgentHolder>(relaxed = true)
    )

    /** 公共桩：DataStore 落临时目录、外部工作区开关、~ 展开按原样。 */
    private fun stubCommon() {
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        every { generalSettingsRepository.deleteExternalWorkspaceSessionsFlow } returns MutableStateFlow(false)
        every { pathHomeResolver.expandHome(any()) } answers { firstArg<String>() }
    }

    /** 一个把 [output] 当作 stdout、[exitCode] 当作退出码的 exec 会话。 */
    private fun execSession(output: String, exitCode: Int = 0): Session.Command {
        val command = mockk<Session.Command>(relaxed = true)
        every { command.inputStream } returns ByteArrayInputStream(output.toByteArray())
        every { command.exitStatus } returns exitCode
        every { command.close() } returns Unit
        return command
    }

    @Test
    fun deleteWorkspace_local_targetExists_deletesDirButKeepsSessions() = runBlocking {
        stubCommon()
        every { executionModeHolder.currentMode() } returns ExecutionMode.LOCAL_PROOT

        // 预置一个非默认内部工作区，避免删除后被 createLocalFallbackWorkspace 用同名重建而看不出变化。
        val projDir = File(projectsRoot, "projA").apply { mkdirs() }
        val marker = File(projDir, "marker.txt").apply { writeText("keep") }

        val repo = newRepo()
        repo.initialize()
        // 目标工作区真实存在（旧实现里 target?.let 才会触发级联删除）。
        assertEquals(projDir.absolutePath, repo.currentPathOrNull())
        assertTrue(repo.workspaces.value.any { it.name == "projA" })

        repo.deleteWorkspace("projA")

        // 回归断言：会话不被级联清理（旧实现此处会调用一次 deleteSessionsByWorkspace）。
        coVerify(exactly = 0) { sessionUseCase.deleteSessionsByWorkspace(any()) }
        // 副作用确实发生：目录内容被删（projA 被移出列表，旧目录不再有 marker）。
        assertFalse("projA 应被移出工作区列表", repo.workspaces.value.any { it.name == "projA" })
        assertFalse("projA 目录内容应已被删除", marker.exists())
    }

    @Test
    fun deleteWorkspace_remote_targetExists_rmIssuedButKeepsSessions() = runBlocking {
        stubCommon()
        every { executionModeHolder.currentMode() } returns ExecutionMode.REMOTE_SSH
        every { remoteSshConnection.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { remoteSshConnection.isConnected() } returns true
        every { remoteSshConnection.config } returns RemoteConnectionConfig(
            host = "example.test",
            port = 22,
            username = "deploy",
            auth = RemoteAuth.Password("secret"),
            remoteWorkspacePath = remoteRoot
        )
        // remoteSshConnection 是 relaxed mock：suspend 的 updateWorkspaceSymlink 与
        // workspaceSymlinkCommand 无需显式桩，列目录/删除/exit 码由下面的 startExecSession 统一供给。
        every { remoteSshConnection.startExecSession(any()) } answers {
            val cmd = firstArg<String>()
            remoteCommands += cmd
            when {
                // 删除命令：标记已删，供后续列目录返回空
                cmd.contains("rm -rf") -> {
                    remoteDeleted = true
                    execSession("", 0)
                }
                // 扫描/刷新列目录：删除前返回 default，删除后返回空
                cmd.contains("for d in") -> execSession(
                    if (remoteDeleted) "" else "MK:0\ndefault\nLS:done",
                    0
                )
                else -> execSession("", 0)
            }
        }

        val repo = newRepo()
        repo.initialize()
        // 目标工作区真实存在（这是本用例能回归旧行为的前提）。
        assertEquals("$remoteRoot/default", repo.currentPathOrNull())
        assertTrue(repo.workspaces.value.any { it.name == "default" })

        repo.deleteWorkspace("default")

        // 回归断言：会话不被级联清理（旧实现此处会调用一次 deleteSessionsByWorkspace）。
        coVerify(exactly = 0) { sessionUseCase.deleteSessionsByWorkspace(any()) }
        // 副作用确实发生：真的发出了指向该工作区的 rm -rf，且工作区列表移除该项。
        assertTrue(
            "应发出删除 default 的远程命令，实际: $remoteCommands",
            remoteCommands.any { it.contains("rm -rf") && it.contains("$remoteRoot/default") }
        )
        assertFalse("default 应被移出工作区列表", repo.workspaces.value.any { it.name == "default" })
    }
}
