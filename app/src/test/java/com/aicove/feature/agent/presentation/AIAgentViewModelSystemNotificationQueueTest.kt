package com.aicove.feature.agent.presentation

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.viewModelScope
import com.aicove.feature.agent.domain.container.ContainerProfile
import com.aicove.feature.agent.domain.container.RootfsSource
import com.aicove.feature.agent.domain.notification.AgentNotificationCenter
import com.aicove.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicove.feature.agent.domain.notification.AgentNotificationKind
import com.aicove.feature.agent.domain.notification.NotificationOutcome
import com.aicove.feature.agent.domain.notification.PendingNotification
import com.aicove.feature.agent.data.local.dao.AgentMessageDao
import com.aicove.feature.agent.data.local.entity.AgentMessageEntity
import com.aicove.feature.agent.data.local.entity.ChatSessionEntity
import com.aicove.feature.agent.domain.engine.AgentEngine
import com.aicove.feature.agent.domain.model.AgentContext
import com.aicove.feature.agent.domain.model.AgentImage
import com.aicove.feature.agent.domain.model.AgentMessage
import com.aicove.feature.agent.domain.model.AgentMode
import com.aicove.feature.agent.domain.model.GoalStatus
import com.aicove.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicove.feature.agent.domain.workflow.AgentEvent
import com.aicove.feature.agent.domain.workflow.AgentWorkflow
import com.aicove.feature.agent.domain.session.SessionUseCase
import com.aicove.feature.terminal.domain.TabFinishedEvent
import com.aicove.feature.terminal.domain.TerminalSessionManager
import com.aicove.feature.terminal.domain.TerminalSessionProvider
import com.aicove.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.firstArg
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「待发送」面板只承载用户消息。
 *
 * 后台任务完成、子代理结束这类系统通知在会话忙碌时本来由 [AgentNotificationCenter] 排队、靠工具结果
 * 搭车或本轮收尾的 flush 送达；一旦中途经过 [AIAgentViewModel.enqueueAgentRequest] 的入队分支，就会
 * 出现在输入框上方的待发送面板里，冒充用户自己排队的消息（用户报告：系统通知撞到排队中的消息中）。
 *
 * 这里钉住三条入口语义：带 systemNotifications 的调用在忙碌时回投通知中心且不进面板；不带它的调用
 * （用户消息）忙碌时照旧进面板；后台完成事件经 deliverSystemEvent 进到同一条系统通知路径。
 *
 * 忙碌状态直接写 [AIAgentViewModel] 的 sessionJobs（放一个活跃 Job），而不是真的发一条消息起一轮：
 * 真实轮次会拉起 workflow / Room / 唤醒锁，在 JVM 单测里既跑不动也不稳定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIAgentViewModelSystemNotificationQueueTest {

    private companion object {
        const val SID = "session-1"
        const val WORKSPACE = "/workspace"
    }

    private lateinit var viewModel: AIAgentViewModel
    private lateinit var notificationCenter: AgentNotificationCenter
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val messageDao = mockk<AgentMessageDao>(relaxed = true)
    private val workflow = mockk<AgentWorkflow>(relaxed = true)
    private val engine = mockk<AgentEngine>(relaxed = true)
    private val generalSettings = mockk<com.aicove.feature.settings.data.repository.GeneralSettingsRepository>(relaxed = true)
    private val fileAccess = mockk<FileAccessProvider>(relaxed = true) {
        every { exists(any()) } returns true
        every { isDirectory(any()) } returns true
    }
    private val sessions = mockk<SessionUseCase>(relaxed = true) {
        coEvery { getSessionById(any()) } returns null
    }
    private val mainDispatcher = UnconfinedTestDispatcher()
    /** 后台命令完成事件流：真实的 [TabFinishedEvent] 从这里进 [AIAgentViewModel.handleBackgroundCommandFinished]。 */
    private val tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 1)

    @Before
    fun setUp() {
        // viewModelScope 走 Dispatchers.Main.immediate；Unconfined 让 stateIn 与入队路径同步生效，
        // 断言之间不需要等待。
        Dispatchers.setMain(mainDispatcher)
        notificationCenter = AgentNotificationCenter()
        every { messageDao.getMessagesBySessionPaged(any(), any()) } returns flowOf(emptyList())
        every { messageDao.getContextSummaryRows(any()) } returns flowOf(emptyList())
        viewModel = newViewModel()
        // queuedRequests 按 _currentSessionId 取队列，先切到被测会话。
        viewModel.selectSession(SID)
    }

    @After
    fun tearDown() {
        // viewModelScope 不随测试结束：不取消它，init 里的 collect / stateIn(Eagerly) 会挂到后续测试上。
        // 必须在 resetMain 之前等待所有协程退出，否则 DefaultDispatcher 上的轮询循环仍可能访问
        // 已被 reset 的 Dispatchers.Main，导致 UncaughtExceptionsBeforeTest 泄漏到后续测试。
        if (::viewModel.isInitialized) {
            val scope = viewModel.viewModelScope
            runBlocking {
                withTimeout(3_000) {
                    checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
                }
            }
        }
        Dispatchers.resetMain()
    }

    @Test
    fun reopeningSessionResetsOlderPageLimitButSearchHitExpandsIt() = runBlocking {
        val hitTimestamp = 123L
        coEvery { messageDao.getMessageById("old-hit") } returns AgentMessageEntity(
            id = "old-hit",
            sessionId = SID,
            role = MessageRole.USER.name,
            content = "search hit",
            timestamp = hitTimestamp,
        )
        coEvery { messageDao.countMessagesFromTimestamp(SID, hitTimestamp) } returns 100

        viewModel.openChatSearchHit(
            ChatSearchHit(SID, "session", "old-hit", "search hit", hitTimestamp)
        )
        val searchWindow = withTimeout(3_000) {
            viewModel.messagesState.first { it.sessionId == SID && it.limit == 105 }
        }
        assertEquals(105, searchWindow.limit)

        viewModel.selectSession("other-session")
        withTimeout(3_000) {
            viewModel.messagesState.first { it.sessionId == "other-session" && it.limit == 30 }
        }
        viewModel.selectSession(SID)

        val reopened = withTimeout(3_000) {
            viewModel.messagesState.first { it.sessionId == SID && it.limit == 30 }
        }
        assertEquals(30, reopened.limit)
    }

    @Test
    fun `会话忙碌时系统通知回投通知中心，不进待发送面板`() {
        markSessionBusy()
        val item = backgroundTaskNotification("term-1")

        viewModel.enqueueAgentRequest(
            request = AgentNotificationFormatter.buildMessage(listOf(item)),
            projectRoot = WORKSPACE,
            targetSessionId = SID,
            systemNotifications = listOf(item)
        )

        assertTrue("系统通知不能出现在待发送面板", viewModel.queuedRequests.value.isEmpty())
        assertEquals("通知必须留在通知中心等待搭车/flush 送达", 1, notificationCenter.pendingCount(SID))
        assertEquals("term-1", notificationCenter.peek(SID).single().sourceId)
    }

    @Test
    fun `会话忙碌时用户消息照旧进待发送面板`() {
        markSessionBusy()

        viewModel.enqueueAgentRequest(
            request = "帮我改一下这个函数",
            projectRoot = WORKSPACE,
            targetSessionId = SID
        )

        val queued = viewModel.queuedRequests.value
        assertEquals("用户消息在忙碌时必须进待发送面板", 1, queued.size)
        assertEquals("帮我改一下这个函数", queued.single().request)
        assertEquals("用户消息不该占用通知中心", 0, notificationCenter.pendingCount(SID))
    }

    @Test
    fun `后台命令完成通知在会话忙碌时进通知中心，不进待发送面板`() {
        markSessionBusy()

        tabFinishedEvents.tryEmit(
            TabFinishedEvent(
                tabId = "term-9",
                title = "后台构建",
                command = "./gradlew :app:assembleUniversalDebug",
                exitCode = 0,
                sourceSessionId = SID
            )
        )
        mainDispatcher.scheduler.runCurrent()

        assertTrue("系统通知不能出现在待发送面板", viewModel.queuedRequests.value.isEmpty())
        assertEquals(1, notificationCenter.pendingCount(SID))
        assertEquals("term-9", notificationCenter.peek(SID).single().sourceId)
    }

    @Test
    fun preparingIsVisibleBeforeSessionValidationFinishes() = runBlocking {
        val validationStarted = CompletableDeferred<Unit>()
        coEvery { sessions.getSessionById(SID) } coAnswers {
            validationStarted.complete(Unit)
            awaitCancellation()
        }
        viewModel.enqueueAgentRequest("hello", projectRoot = WORKSPACE, targetSessionId = SID, clientMessageId = "new-user")
        withTimeout(3_000) { validationStarted.await() }

        assertTrue(viewModel.agentState.value is AgentUIState.Preparing)
        assertEquals("new-user", viewModel.pendingUserMessages[SID].single().id)
        viewModel.selectSession("other-session")
        assertEquals(AgentUIState.Idle, viewModel.agentState.value)
        assertTrue(viewModel.agentStates.value[SID] is AgentUIState.Preparing)
        viewModel.stopAgentSession(SID)
        assertEquals(AgentUIState.Idle, viewModel.agentStates.value[SID])
        assertTrue(viewModel.pendingUserMessages[SID].isEmpty())
    }

    @Test
    fun historyFailureReplacesPreparingWithError() = runBlocking {
        coEvery { persistence.buildHistory(any(), any()) } throws IllegalStateException("history failed")
        viewModel.enqueueAgentRequest("hello", projectRoot = WORKSPACE, targetSessionId = SID, clientMessageId = "failed-user")

        withTimeout(3_000) { viewModel.agentStates.first { it[SID] is AgentUIState.Error } }
        assertTrue(viewModel.pendingUserMessages[SID].isEmpty())
    }

    @Test
    fun deletingSessionClearsItsMessageUiStateAndPreservesOtherSessions() {
        val otherSession = "other-session"
        viewModel.turnMessageKeys[SID] = mutableMapOf(
            "user-1" to "turn:user-1",
            "tool-1" to "turn:user-1",
            "assistant-1" to "turn:user-1",
        )
        viewModel.turnMessageKeys[otherSession] = mutableMapOf("other-user" to "turn:other-user")
        viewModel.setToolExpanded("tool-1", true)
        viewModel.setToolExpanded("toolgroup:tool-1", true)
        viewModel.setToolExpanded("other-tool", true)
        viewModel.setTurnExpanded("turn:user-1", true)
        viewModel.setTurnExpanded("turn:other-user", true)
        viewModel.setReasoningExpanded("assistant-1", true)
        viewModel.setReasoningExpanded(viewModel.streamingReasoningKey(SID), true)
        viewModel.setReasoningExpanded("other-assistant", true)
        coEvery { sessions.deleteSession(SID) } returns listOf(SID)

        viewModel.deleteSession(SID)
        mainDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.turnMessageKeys.containsKey(SID))
        assertFalse(viewModel.toolExpansionOverrides.containsKey("tool-1"))
        assertFalse(viewModel.toolExpansionOverrides.containsKey("toolgroup:tool-1"))
        assertFalse(viewModel.turnExpansionOverrides.containsKey("turn:user-1"))
        assertFalse(viewModel.reasoningExpansionOverrides.containsKey("assistant-1"))
        assertFalse(viewModel.reasoningExpansionOverrides.containsKey(viewModel.streamingReasoningKey(SID)))
        assertEquals(mapOf("other-user" to "turn:other-user"), viewModel.turnMessageKeys[otherSession])
        assertEquals(true, viewModel.toolExpansionOverrides["other-tool"])
        assertEquals(true, viewModel.turnExpansionOverrides["turn:other-user"])
        assertEquals(true, viewModel.reasoningExpansionOverrides["other-assistant"])
    }

    @Test
    fun stopEchoesTheEditedQueuedRequestWhileHistoryIsStillLoading() = runBlocking {
        val historyStarted = CompletableDeferred<Unit>()
        coEvery { persistence.buildHistory(any(), any()) } coAnswers {
            historyStarted.complete(Unit)
            awaitCancellation()
        }
        markSessionBusy()
        val attachment = AgentAttachment("note.txt", "/workspace/note.txt", "/local/note.txt", "text/plain", 2L, false)
        viewModel.enqueueAgentRequest(
            request = "original",
            projectRoot = WORKSPACE,
            targetSessionId = SID,
            clientMessageId = "queued-user",
            inputAttachments = listOf(attachment)
        )
        val queued = viewModel.queuedRequests.value.single()
        viewModel.updateQueuedRequest(queued.id, "edited")
        assertTrue(viewModel.pendingUserMessages[SID].isEmpty())

        viewModel.stopAgent()
        withTimeout(3_000) { historyStarted.await() }
        assertTrue(viewModel.agentStates.value[SID] is AgentUIState.Preparing)
        assertTrue(viewModel.queuedRequests.value.isEmpty())
        assertEquals(PendingUserMessage("queued-user", "edited", listOf(attachment)), viewModel.pendingUserMessages[SID].single())
        viewModel.selectSession("other-session")
        assertTrue(viewModel.pendingUserMessages["other-session"].isEmpty())
        assertFalse(viewModel.pendingUserMessages[SID].isEmpty())
    }

    private fun backgroundTaskNotification(tabId: String) = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = "后台构建",
        outcome = NotificationOutcome.COMPLETED,
        exitCode = 0
    )

    @Test
    fun failedPartialAssistant_isPersistedInterrupted_andDoesNotCompleteMemoryOrGoal() = runBlocking {
        val session = ChatSessionEntity(
            id = SID,
            title = "test",
            createdAt = 0,
            updatedAt = 0,
            workspacePath = WORKSPACE,
            goal = "finish task",
            goalStatus = GoalStatus.ACTIVE.name,
            providerId = "bound-provider",
            model = "bound-model"
        )
        coEvery { sessions.getSessionById(SID) } returns session
        coEvery { persistence.buildHistory(SID, any()) } returns emptyList()
        coEvery { generalSettings.goalMaxTurns() } returns 5
        coEvery { generalSettings.goalMaxTokens() } returns 10_000
        every { workflow.executeEvents(any(), any(), any()) } returns flowOf(
            AgentEvent.AssistantText("partial", reasoning = "thinking", isInterrupted = true),
            AgentEvent.Failed("network error")
        )
        viewModel.setWorkspace(WORKSPACE)

        withTimeout(5_000) {
            viewModel.executeAgentRequestStream(
                request = "input",
                projectRoot = WORKSPACE,
                targetSessionId = SID
            ).join()
        }

        coVerify {
            persistence.persist(
                SID,
                MessageRole.ASSISTANT,
                "partial",
                id = any(),
                reasoning = "thinking",
                isError = true
            )
        }
        verify(exactly = 0) { engine.onTurnCompleted(any()) }
        coVerify(exactly = 0) { sessions.incrementGoalTurn(SID) }
        coVerify(exactly = 0) { sessions.updateGoalStatus(SID, GoalStatus.EXHAUSTED) }
        verify(exactly = 1) { workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun regenerateInterruptedAnswer_reusesUserRowAndImageContext_withoutPersistingAnotherUser() = runBlocking {
        val userId = "user-original"
        val assistantId = "assistant-partial"
        val image = AgentImage("image/png", "base64-data", "~/workspace/image.png")
        val userContent = "describe this image"
        val history = listOf(
            AgentMessage.UserMessage(id = userId, content = userContent, images = listOf(image)),
            AgentMessage.AssistantMessage(id = assistantId, content = "partial", reasoning = "thinking")
        )
        val rows = mutableListOf(
            AgentMessageEntity(userId, SID, MessageRole.USER.name, "describe this image", 1),
            AgentMessageEntity(assistantId, SID, MessageRole.ASSISTANT.name, "partial", 2, isError = true)
        )
        val session = ChatSessionEntity(
            id = SID,
            title = "test",
            createdAt = 0,
            updatedAt = 0,
            workspacePath = WORKSPACE,
            mode = AgentMode.PLAN.name,
            providerId = "bound-provider",
            model = "bound-model"
        )
        coEvery { messageDao.getMessageById(assistantId) } returns rows.last()
        coEvery { messageDao.getMessagesBySessionOnce(SID) } coAnswers { rows.toList() }
        coEvery { messageDao.insert(any()) } coAnswers {
            val updated = firstArg<AgentMessageEntity>()
            val index = rows.indexOfFirst { it.id == updated.id }
            if (index >= 0) rows[index] = updated
        }
        coEvery { persistence.buildHistory(SID, any()) } coAnswers {
            history.filterNot { it is AgentMessage.AssistantMessage && it.id == assistantId }
        }
        coEvery { sessions.getSessionById(SID) } returns session
        val requests = mutableListOf<Pair<String, AgentContext>>()
        every { workflow.executeEvents(any(), any(), any()) } answers {
            requests += firstArg<String>() to secondArg<AgentContext>()
            flowOf(AgentEvent.Failed("test end"))
        }
        viewModel.setWorkspace(WORKSPACE)

        withTimeout(5_000) {
            viewModel.regenerateInterruptedAssistant(assistantId).join()
        }

        assertEquals(1, requests.size)
        val (request, context) = requests.single()
        assertEquals(userContent, request)
        assertEquals(userId, context.userMessageId)
        assertEquals(SID, context.sessionId)
        assertEquals(emptyList<AgentMessage>(), context.history)
        assertEquals(listOf(image), context.inputImages)
        assertEquals(AgentMode.PLAN, context.mode)
        coVerify(exactly = 1) {
            messageDao.insert(match {
                it.id == assistantId && it.content == "partial" && it.isError && it.isContextExcluded
            })
        }
        val retainedPartial = rows.firstOrNull { it.id == assistantId }
        assertEquals("partial", retainedPartial?.content)
        assertEquals(true, retainedPartial?.isError)
        assertEquals(true, retainedPartial?.isContextExcluded)
        coVerify(exactly = 0) { messageDao.deleteMessageById(assistantId) }
        coVerify(exactly = 0) { persistence.persist(SID, MessageRole.USER, any(), id = any(), attachments = any()) }
    }

    @Test
    fun regenerateInterruptedAnswerWithToolSideEffect_isRejected() = runBlocking {
        val user = AgentMessageEntity("user", SID, MessageRole.USER.name, "write file", 1)
        val tool = AgentMessageEntity("tool", SID, MessageRole.TOOL.name, "written", 2, toolCallId = "call")
        val assistant = AgentMessageEntity(
            "assistant",
            SID,
            MessageRole.ASSISTANT.name,
            "partial",
            3,
            isError = true
        )
        coEvery { messageDao.getMessageById("assistant") } returns assistant
        coEvery { messageDao.getMessagesBySessionOnce(SID) } returns listOf(user, tool, assistant)
        coEvery { sessions.getSessionById(SID) } returns ChatSessionEntity(
            id = SID,
            title = "test",
            createdAt = 0,
            updatedAt = 0,
            workspacePath = WORKSPACE
        )
        viewModel.setWorkspace(WORKSPACE)

        withTimeout(5_000) {
            viewModel.regenerateInterruptedAssistant("assistant").join()
        }

        coVerify(exactly = 0) { persistence.buildHistory(SID, any()) }
        verify(exactly = 0) { workflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun assistantErrorMapsToInterrupted_butToolErrorDoesNot() {
        val assistant = AgentMessageEntity("a", SID, MessageRole.ASSISTANT.name, "partial", 1, isError = true)
        val tool = AgentMessageEntity("t", SID, MessageRole.TOOL.name, "tool failed", 2, isError = true)

        assertTrue(isInterruptedAssistantRole(assistant.role, assistant.isError))
        assertFalse(isInterruptedAssistantRole(tool.role, tool.isError))
        assertTrue(tool.toUIMessage().isError)
    }

    /** 只改一个私有映射：会话视为「有一轮正在跑」，但不启动任何真实轮次。 */
    private fun markSessionBusy() {
        val field = AIAgentViewModel::class.java.getDeclaredField("sessionJobs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(viewModel) as MutableMap<String, Job>
        jobs[SID] = Job()
    }

    /**
     * 其余构造依赖与本路径无关，一律 relaxed mock；只有依赖的 Flow 需要桩成会发值/能挂住的对象，
     * 否则 init 里的 collect 立刻空转或拿到 null。
     */
    private fun newViewModel(): AIAgentViewModel = AIAgentViewModel(
        agentWorkflow = workflow,
        toolRegistry = mockk(relaxed = true),
        agentEngine = engine,
        agentMessageDao = messageDao,
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
        sessionUseCase = sessions,
        messagePersistenceUseCase = persistence,
        agentChatRequestBus = mockk(relaxed = true) {
            coEvery { receiveOrNull(any()) } coAnswers { awaitCancellation() }
        },
        agentRunStateStore = mockk(relaxed = true),
        planApprovalManager = mockk(relaxed = true),
        terminalSessionManager = mockk<TerminalSessionManager>(relaxed = true).also { m ->
            every { m.tabFinishedEvents } returns MutableSharedFlow()
        },
        remoteTerminalSessionManager = mockk(relaxed = true),
        terminalSessionProvider = mockk<TerminalSessionProvider>(relaxed = true).also { m ->
            every { m.tabFinishedEvents } returns tabFinishedEvents
        },
        slashCommandRegistry = mockk(relaxed = true) {
            // init 里 _currentWorkspace.collect 会立刻触发 refresh()：真实实现会扫技能目录。
            coEvery { refresh() } just runs
            every { resolve(any()) } returns null
        },
        fileMentionRepository = mockk(relaxed = true),
        checkpointManager = mockk(relaxed = true),
        checkpointDao = mockk(relaxed = true),
        backupManager = mockk(relaxed = true),
        mcpManager = mockk(relaxed = true),
        agentSoundSettings = mockk(relaxed = true),
        generalSettingsRepository = generalSettings,
        keepaliveSettings = mockk(relaxed = true),
        subAgentEventBus = mockk(relaxed = true) { every { events } returns MutableSharedFlow() },
        agentNotificationCenter = notificationCenter,
        agentDefinitionRepository = mockk(relaxed = true),
        todoItemDao = mockk(relaxed = true),
        fileAccess = fileAccess,
        fileChangeHub = mockk(relaxed = true),
        workspaceWriteSignal = mockk(relaxed = true) { every { writes } returns MutableSharedFlow() },
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
        containerOsDetector = mockk(relaxed = true) { every { osMap } returns MutableStateFlow(emptyMap()) },
        liveNotificationSettings = mockk(relaxed = true) { every { enabledFlow } returns MutableStateFlow(false) },
        agentLiveNotificationManager = mockk(relaxed = true),
        approvalNotificationSettings = mockk(relaxed = true) { every { enabledFlow } returns MutableStateFlow(false) },
        agentApprovalNotificationManager = mockk(relaxed = true),
        context = mockk<Context>(relaxed = true) {
            every { getSystemService(Context.POWER_SERVICE) } returns mockk<PowerManager>(relaxed = true)
        }
    )
}
