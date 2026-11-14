package com.aicove.feature.agent.presentation

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import com.aicove.R
import com.aicove.feature.agent.data.local.entity.ChatSessionEntity
import com.aicove.feature.agent.domain.container.ContainerProfile
import com.aicove.feature.agent.domain.container.RootfsSource
import com.aicove.feature.agent.domain.session.SessionUseCase
import com.aicove.feature.terminal.domain.TerminalSessionManager
import com.aicove.feature.terminal.domain.TerminalSessionProvider
import com.aicove.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * 跨工作区执行拒绝回归：会话绑定的 workspacePath 与本次执行目标（projectRoot，空则回退当前工作区）
 * 不一致、或绑定目录不可用时，请求必须在入口被拦截并给出可见 Error，不允许工具落到错目录。
 *
 * 旧行为：executeAgentRequestStream 不校验绑定，跨工作区会话也能直接执行。
 * 新行为：入口校验（A）——
 *   targetWs = projectRoot.ifBlank { _currentWorkspace.value }
 *   targetUnknown / pathMismatch / dirMissing（fileAccess.exists+isDirectory）任一成立即拒绝。
 *
 * 断言用哨兵文案区分「被守卫拒绝」与「下游未桩异常」：把三个跨工作区文案键桩成固定串，
 * 只看 state 的 message 是否命中哨兵，避免 relaxed 依赖下游抛错把 allowed 用例误判。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIAgentViewModelCrossWorkspaceExecutionTest {

    private companion object {
        const val WORKSPACE_A = "/ws/a"
        const val WORKSPACE_B = "/ws/b"
        const val SESSION_A = "session-a"

        const val SENTINEL_PATH_MISMATCH = "SENTINEL_PATH_MISMATCH"
        const val SENTINEL_DIR_MISSING = "SENTINEL_DIR_MISSING"
        const val SENTINEL_NO_WORKSPACE = "SENTINEL_NO_WORKSPACE"

        val REJECT_SENTINELS = setOf(SENTINEL_PATH_MISMATCH, SENTINEL_DIR_MISSING, SENTINEL_NO_WORKSPACE)
    }

    private lateinit var viewModel: AIAgentViewModel
    private val sessionUseCase = mockk<SessionUseCase>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { sessionUseCase.getSessionById(SESSION_A) } returns ChatSessionEntity(
            id = SESSION_A,
            title = "A 的会话",
            createdAt = 0L,
            updatedAt = 0L,
            workspacePath = WORKSPACE_A
        )
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    /** 命中守卫拒绝时返回命中的哨兵文案；否则 null（含下游异常、正常执行）。 */
    private fun rejectionMessage(): String? =
        (viewModel.agentStates.value[SESSION_A] as? AgentUIState.Error)
            ?.message
            ?.takeIf { it in REJECT_SENTINELS }

    private fun assertRejected(job: Job, expected: String) {
        awaitGuard(job)
        assertEquals("应被跨工作区守卫以「$expected」拒绝", expected, rejectionMessage())
    }

    private fun assertNotGuardRejected(job: Job) {
        awaitGuard(job)
        assertFalse("同工作区/可回退场景不应被跨工作区守卫拒绝", rejectionMessage() != null)
    }

    /**
     * 守卫里的目录存在性检查走 withContext(Dispatchers.IO)，会让落状态异步于 executeAgentRequestStream 返回；
     * 给上限等待让它完成（allowed 用例下游可能还在跑，超时返回 null 也可，断言只看守卫是否命中哨兵）。
     */
    private fun awaitGuard(job: Job) {
        runBlocking { withTimeoutOrNull(3_000) { job.join() } }
    }

    private fun newViewModel(dirExists: Boolean = true): AIAgentViewModel {
        val context = mockk<Context>(relaxed = true).also { ctx ->
            every { ctx.getString(R.string.cross_workspace_path_mismatch, *anyVararg()) } returns SENTINEL_PATH_MISMATCH
            every { ctx.getString(R.string.cross_workspace_dir_missing, *anyVararg()) } returns SENTINEL_DIR_MISSING
            every { ctx.getString(R.string.cross_workspace_no_workspace) } returns SENTINEL_NO_WORKSPACE
            every { ctx.getSystemService(Context.POWER_SERVICE) } returns mockk<PowerManager>(relaxed = true)
        }
        return AIAgentViewModel(
            agentWorkflow = mockk(relaxed = true),
            toolRegistry = mockk(relaxed = true),
            agentEngine = mockk(relaxed = true),
            agentMessageDao = mockk(relaxed = true),
            agentDatabase = mockk(relaxed = true),
            chatSessionDao = mockk(relaxed = true),
            llmCallRecordDao = mockk(relaxed = true),
            modelCostCalculator = mockk(relaxed = true),
            aiProviderRepository = mockk(relaxed = true),
            defaultModelSettingsRepository = mockk(relaxed = true),
            modelReasoningEffortRepository = mockk(relaxed = true),
            toolPermissionManager = mockk(relaxed = true),
            askUserQuestionManager = mockk(relaxed = true),
            containerEngine = mockk(relaxed = true),
            sessionUseCase = sessionUseCase,
            messagePersistenceUseCase = mockk(relaxed = true),
            planApprovalManager = mockk(relaxed = true),
            terminalSessionManager = mockk<TerminalSessionManager>(relaxed = true) {
                every { tabFinishedEvents } returns MutableSharedFlow()
            },
            remoteTerminalSessionManager = mockk(relaxed = true),
            terminalSessionProvider = mockk<TerminalSessionProvider>(relaxed = true) {
                every { tabFinishedEvents } returns MutableSharedFlow()
            },
            slashCommandRegistry = mockk {
                coEvery { refresh() } just runs
                every { resolve(any()) } returns null
                every { findTriggered(any(), any()) } returns emptyList()
                every { commands } returns MutableStateFlow(emptyList())
            },
            fileMentionRepository = mockk(relaxed = true),
            checkpointManager = mockk(relaxed = true),
            checkpointDao = mockk(relaxed = true),
            backupManager = mockk(relaxed = true),
            mcpManager = mockk(relaxed = true),
            agentSoundSettings = mockk(relaxed = true),
            generalSettingsRepository = mockk(relaxed = true),
            keepaliveSettings = mockk(relaxed = true),
            subAgentEventBus = mockk(relaxed = true) {
                every { events } returns MutableSharedFlow()
            },
            agentNotificationCenter = mockk(relaxed = true),
            agentDefinitionRepository = mockk(relaxed = true),
            todoItemDao = mockk(relaxed = true),
            // dirMissing 校验走 fileAccess.exists/isDirectory；按用例控制是否「目录可用」。
            fileAccess = mockk<FileAccessProvider>(relaxed = true).also { fa ->
                every { fa.exists(any()) } returns dirExists
                every { fa.isDirectory(any()) } returns dirExists
            },
            fileChangeHub = mockk(relaxed = true),
            workspaceWriteSignal = mockk(relaxed = true) {
                every { writes } returns MutableSharedFlow()
            },
            contextUsageHolder = mockk(relaxed = true),
            containerSettingsRepository = mockk(relaxed = true) {
                every { activeProfileFlow } returns MutableStateFlow(
                    ContainerProfile(
                        id = "builtin",
                        name = "Alpine",
                        rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
                        shellPath = null,
                        isBuiltin = true
                    )
                )
            },
            containerOsDetector = mockk(relaxed = true) {
                every { osMap } returns MutableStateFlow(emptyMap())
            },
            liveNotificationSettings = mockk(relaxed = true) {
                every { enabledFlow } returns MutableStateFlow(false)
            },
            agentLiveNotificationManager = mockk(relaxed = true),
            approvalNotificationSettings = mockk(relaxed = true) {
                every { enabledFlow } returns MutableStateFlow(false)
            },
            agentApprovalNotificationManager = mockk(relaxed = true),
            context = context
        )
    }

    /** 会话绑定 A、当前工作区 B、projectRoot B → 目标与绑定不一致，拒绝。 */
    @Test
    fun executeAgentRequestStream_crossWorkspace_rejected() {
        viewModel = newViewModel()
        viewModel.selectSession(SESSION_A)
        viewModel.setWorkspace(WORKSPACE_B)

        val job = viewModel.executeAgentRequestStream(
            request = "hello",
            projectRoot = WORKSPACE_B,
            targetSessionId = SESSION_A
        )

        assertRejected(job, SENTINEL_PATH_MISMATCH)
    }

    /** 绑定 A、当前 A、projectRoot A、目录可用 → 放行。 */
    @Test
    fun executeAgentRequestStream_sameWorkspace_allowed() {
        viewModel = newViewModel(dirExists = true)
        viewModel.selectSession(SESSION_A)
        viewModel.setWorkspace(WORKSPACE_A)

        val job = viewModel.executeAgentRequestStream(
            request = "hello",
            projectRoot = WORKSPACE_A,
            targetSessionId = SESSION_A
        )

        assertNotGuardRejected(job)
    }

    /** 绑定 A、当前 A、projectRoot A，但目录已不可用 → dirMissing 拒绝。 */
    @Test
    fun executeAgentRequestStream_sameWorkspaceButDirMissing_rejected() {
        viewModel = newViewModel(dirExists = false)
        viewModel.selectSession(SESSION_A)
        viewModel.setWorkspace(WORKSPACE_A)

        val job = viewModel.executeAgentRequestStream(
            request = "hello",
            projectRoot = WORKSPACE_A,
            targetSessionId = SESSION_A
        )

        assertRejected(job, SENTINEL_DIR_MISSING)
    }

    /**
     * 空 projectRoot 不能绕过校验：入口把「projectRoot 为空 / 当前工作区为空」视为目标未知，直接拒绝。
     * 这条钉住「projectRoot 为空就跳过」的旧实现漏洞。
     */
    @Test
    fun executeAgentRequestStream_emptyProjectRoot_rejectedAsUnknown() {
        viewModel = newViewModel()
        viewModel.selectSession(SESSION_A)
        viewModel.setWorkspace(WORKSPACE_B)

        val job = viewModel.executeAgentRequestStream(
            request = "hello",
            projectRoot = "",
            targetSessionId = SESSION_A
        )

        assertRejected(job, SENTINEL_NO_WORKSPACE)
    }

    /** 即便当前工作区就是绑定工作区，空 projectRoot 仍被视为目标未知而拒绝，绝不静默放行。 */
    @Test
    fun executeAgentRequestStream_emptyProjectRoot_sameCurrentWorkspace_stillRejected() {
        viewModel = newViewModel(dirExists = true)
        viewModel.selectSession(SESSION_A)
        viewModel.setWorkspace(WORKSPACE_A)

        val job = viewModel.executeAgentRequestStream(
            request = "hello",
            projectRoot = "",
            targetSessionId = SESSION_A
        )

        assertRejected(job, SENTINEL_NO_WORKSPACE)
    }
}
