package com.aicove.feature.agent.presentation

import androidx.lifecycle.viewModelScope
import com.aicove.core.watch.FileChangeBatch
import com.aicove.core.watch.FileChangeHub
import com.aicove.core.watch.WorkspaceWriteSignal
import com.aicove.feature.agent.domain.container.ContainerProfile
import com.aicove.feature.agent.domain.container.RootfsSource
import com.aicove.feature.workspace.domain.FileAccessProvider
import com.aicove.feature.workspace.domain.FileEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.mockk
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 文件树「手动刷新进行中」这一帧不能丢。
 *
 * 远程模式下手动刷新要串行重列所有已展开目录，可能数秒；期间继续渲染上一份树（仅置 refreshing），
 * UI 在工具栏叠加进行中指示、并暂时禁用刷新按钮。若退回 Loading 或直接把 nodes 换掉，
 * 滚动位置与旧树内容都会闪一下。本用例钉住这两步的先后与「旧树未被替换」。
 *
 * 观察方式：browseState 是 StateFlow（会合并中间值），用一个 [Dispatchers.Unconfined] 收集器
 * 在每次 emit 处同步取帧；第二次列目录卡在一道闸门上，让「进行中」那帧必定能被观察到。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIAgentViewModelBrowseRefreshTest {

    private lateinit var viewModel: AIAgentViewModel
    private val writeSignal = WorkspaceWriteSignal()
    private val profile = MutableStateFlow(
        ContainerProfile(
            id = "builtin",
            name = "Alpine",
            rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
            shellPath = null,
            isBuiltin = true
        )
    )

    @Before
    fun setUp() {
        // viewModelScope 走 Dispatchers.Main.immediate。
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // viewModelScope 的生命周期不随测试结束：不取消它，ViewModel init 里起的 launch
        // 与 stateIn(Eagerly) 会继续挂在 Dispatchers.Main 上，泄漏到后面的测试。
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
    fun `手动刷新先发一帧旧树进行中态，重列完成后再发新树`() = runBlocking {
        val firstTree = listOf(FileEntry(name = "a.txt", isDirectory = false, size = 1, lastModified = 0))
        val secondTree = firstTree + FileEntry(name = "b.txt", isDirectory = false, size = 2, lastModified = 0)

        val fileAccess = mockk<FileAccessProvider>()
        var listCalls = 0
        val refreshBuilding = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        every { fileAccess.exists(any()) } returns false
        every { fileAccess.listFiles(any()) } answers {
            listCalls += 1
            if (listCalls == 1) {
                firstTree
            } else {
                // 第二次列目录就是手动刷新的重列：卡住它，让「进行中」那一帧能稳定被观察到。
                refreshBuilding.countDown()
                check(releaseRefresh.await(5, TimeUnit.SECONDS))
                secondTree
            }
        }

        viewModel = newFileBrowseViewModel(fileAccess)
        val frames = ConcurrentLinkedQueue<FileBrowseState>()
        val collectJob = launch(Dispatchers.Unconfined) { viewModel.browseState.collect { frames += it } }

        try {
            val first = awaitSuccess(frames) { !it.refreshing }
            // 等刷新触发器订阅就绪：刷新信号经 drop(1) 丢掉订阅时的当前值，抢在订阅前发出会被吞掉。
            delay(200)
            viewModel.refreshBrowse()

            assertTrue("手动刷新应触发重列（第二次 listFiles）", refreshBuilding.await(5, TimeUnit.SECONDS))
            val refreshing = awaitSuccess(frames) { it.refreshing }
            assertSame("进行中帧必须原样复用上一份树，不能替换 nodes", first.nodes, refreshing.nodes)

            releaseRefresh.countDown()
            val updated = awaitSuccess(frames) { !it.refreshing && it.nodes.size == secondTree.size + 2 }
            assertEquals(secondTree.size + 2, updated.nodes.size)
            assertTrue("新树与旧树不是同一份", updated.nodes !== first.nodes)
        } finally {
            releaseRefresh.countDown()
            collectJob.cancelAndJoin()
        }
    }

    @Test
    fun `展开只列新增目录，折叠不列目录，再展开读取折叠期间的新内容`() = runBlocking {
        val calls = ConcurrentLinkedQueue<String>()
        var version = "old.txt"
        val fileAccess = mockk<FileAccessProvider>()
        every { fileAccess.exists(any()) } returns false
        every { fileAccess.listFiles(any()) } answers {
            val path = firstArg<String>()
            calls += path
            when (path) {
                "~/workspace" -> listOf(entry("src", true), entry("docs", true))
                "~/workspace/src" -> listOf(entry(version))
                "~/workspace/docs" -> listOf(entry("guide.txt"))
                else -> error("不应读取 $path")
            }
        }
        viewModel = newFileBrowseViewModel(fileAccess)
        val collector = launch { viewModel.browseState.collect {} }
        try {
            awaitCurrent { it.nodes.any { node -> node.entry.name == "src" } }
            assertEquals(listOf("~/workspace"), calls.toList())
            viewModel.toggleExpand("~/workspace/src")
            awaitCurrent { it.nodes.any { node -> node.entry.name == "old.txt" } }
            assertEquals(listOf("~/workspace", "~/workspace/src"), calls.toList())
            viewModel.toggleExpand("~/workspace/docs")
            awaitCurrent { it.nodes.any { node -> node.entry.name == "guide.txt" } }
            assertEquals(listOf("~/workspace", "~/workspace/src", "~/workspace/docs"), calls.toList())
            calls.clear()

            viewModel.toggleExpand("~/workspace/src")
            awaitCurrent { it.nodes.any { node -> node.path == "~/workspace/src" && !node.isExpanded } }
            assertTrue("折叠不应调用 listFiles", calls.isEmpty())
            version = "new.txt"
            viewModel.toggleExpand("~/workspace/src")
            awaitCurrent { it.nodes.any { node -> node.entry.name == "new.txt" } }
            assertEquals(listOf("~/workspace/src"), calls.toList())
            calls.clear()

            viewModel.toggleExpand("~/workspace")
            awaitCurrent { it.nodes.size == 2 }
            assertTrue("折叠工作区根也不应调用 listFiles", calls.isEmpty())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `AI写入和手动刷新都会使已缓存的根和子目录重新读取`() = runBlocking {
        val calls = ConcurrentLinkedQueue<String>()
        var version = "old.txt"
        val fileAccess = mockk<FileAccessProvider>()
        every { fileAccess.exists(any()) } returns false
        every { fileAccess.listFiles(any()) } answers {
            val path = firstArg<String>()
            calls += path
            if (path == "~/workspace") listOf(entry("src", true), entry(version)) else listOf(entry(version))
        }
        viewModel = newFileBrowseViewModel(fileAccess)
        val collector = launch { viewModel.browseState.collect {} }
        try {
            awaitCurrent { it.nodes.any { node -> node.entry.name == "src" } }
            viewModel.toggleExpand("~/workspace/src")
            awaitCurrent { it.nodes.any { node -> node.path == "~/workspace/src/old.txt" } }
            delay(200)
            calls.clear()
            version = "ai.txt"
            writeSignal.notifyWritten()
            awaitCurrent { it.nodes.count { node -> node.entry.name == "ai.txt" } == 2 }
            assertEquals(listOf("~/workspace", "~/workspace/src"), calls.toList())

            calls.clear()
            version = "manual.txt"
            viewModel.refreshBrowse()
            awaitCurrent { !it.refreshing && it.nodes.count { node -> node.entry.name == "manual.txt" } == 2 }
            assertEquals(listOf("~/workspace", "~/workspace/src"), calls.toList())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `展开集相同的工作区和同名profile切换均丢弃旧目录缓存`() = runBlocking {
        val calls = ConcurrentLinkedQueue<String>()
        var version = "first.txt"
        val fileAccess = mockk<FileAccessProvider>()
        every { fileAccess.exists(any()) } returns false
        every { fileAccess.listFiles(any()) } answers {
            calls += firstArg<String>()
            listOf(entry(version))
        }
        viewModel = newFileBrowseViewModel(fileAccess)
        viewModel.setWorkspace("/projects/first")
        val collector = launch { viewModel.browseState.collect {} }
        try {
            awaitCurrent { it.nodes.any { node -> node.entry.name == "first.txt" } }
            calls.clear()
            version = "second.txt"
            viewModel.setWorkspace("/projects/second")
            awaitCurrent { it.nodes.any { node -> node.entry.name == "second.txt" } }
            assertEquals(listOf("~/workspace"), calls.toList())

            calls.clear()
            version = "profile.txt"
            profile.value = profile.value.copy(id = "another")
            awaitCurrent { it.nodes.any { node -> node.entry.name == "profile.txt" } }
            assertEquals(listOf("~/workspace"), calls.toList())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `目录变更只重读受影响目录，根gitignore变更更新缓存标记`() = runBlocking {
        val rootChanges = MutableSharedFlow<FileChangeBatch>()
        val srcChanges = MutableSharedFlow<FileChangeBatch>()
        val hub = mockk<FileChangeHub> {
            every { watchWorkspace("~/workspace") } returns rootChanges
            every { watchWorkspace("~/workspace/src") } returns srcChanges
        }
        val calls = ConcurrentLinkedQueue<String>()
        var version = "old.txt"
        var ignore = ""
        val fileAccess = mockk<FileAccessProvider>()
        every { fileAccess.exists(any()) } returns true
        every { fileAccess.readFile(any()) } answers { ignore }
        every { fileAccess.listFiles(any()) } answers {
            val path = firstArg<String>()
            calls += path
            if (path == "~/workspace") listOf(entry("src", true)) else listOf(entry(version))
        }
        viewModel = newFileBrowseViewModel(fileAccess, hub)
        val collector = launch { viewModel.browseState.collect {} }
        try {
            awaitCurrent { it.nodes.any { node -> node.entry.name == "src" } }
            viewModel.toggleExpand("~/workspace/src")
            awaitCurrent { it.nodes.any { node -> node.entry.name == "old.txt" } }
            withTimeout(5_000) { srcChanges.subscriptionCount.first { it > 0 } }
            calls.clear()
            version = "new.txt"
            srcChanges.emit(FileChangeBatch(emptyList()))
            awaitCurrent { it.nodes.any { node -> node.entry.name == "new.txt" } }
            assertEquals(listOf("~/workspace/src"), calls.toList())

            calls.clear()
            ignore = "*.txt"
            rootChanges.emit(FileChangeBatch(emptyList()))
            awaitCurrent { it.nodes.any { node -> node.entry.name == "new.txt" && node.ignored } }
            assertEquals(listOf("~/workspace"), calls.toList())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `子目录读取失败保留工作区树并标错，刷新后恢复`() {
        val fileAccess = mockk<FileAccessProvider>()
        every { fileAccess.exists(any()) } returns false
        every { fileAccess.listFiles("~/workspace") } returns listOf(entry("src", true), entry("visible.txt"))
        every { fileAccess.listFiles("~/workspace/src") } throws java.io.IOException("unavailable")
        val loader = BrowseTreeLoader("~/workspace", fileAccess::listFiles, fileAccess::exists, fileAccess::readFile)
        val expanded = setOf("~/workspace", "~/workspace/src")
        val root = FileTreeRoot.Container("Alpine", null, false)

        val failed = loader.build(expanded, root) as FileBrowseState.Success
        assertTrue(failed.nodes.any { it.path == "~/workspace/src" && it.hasError })
        assertTrue(failed.nodes.any { it.entry.name == "visible.txt" })

        every { fileAccess.listFiles("~/workspace/src") } returns listOf(entry("recovered.txt"))
        loader.invalidate("~/workspace/src")
        val recovered = loader.build(expanded, root) as FileBrowseState.Success
        assertTrue(recovered.nodes.any { it.path == "~/workspace/src" && !it.hasError })
        assertTrue(recovered.nodes.any { it.entry.name == "recovered.txt" })
    }

    private fun entry(name: String, directory: Boolean = false) = FileEntry(name, directory, 0, 0)

    private suspend fun awaitCurrent(predicate: (FileBrowseState.Success) -> Boolean) = withTimeout(5_000) {
        viewModel.browseState.first { it is FileBrowseState.Success && predicate(it) }
    }

    /** 取第一帧满足 [predicate] 的 [FileBrowseState.Success]；超时即失败。 */
    private suspend fun awaitSuccess(
        frames: ConcurrentLinkedQueue<FileBrowseState>,
        predicate: (FileBrowseState.Success) -> Boolean
    ): FileBrowseState.Success = withTimeout(5_000) {
        var match: FileBrowseState.Success? = null
        while (match == null) {
            match = frames.firstOrNull { it is FileBrowseState.Success && predicate(it) } as? FileBrowseState.Success
            if (match == null) delay(10)
        }
        match!!
    }

    /**
     * 只为文件树这一条路径起的 ViewModel：它只依赖 [FileAccessProvider] 与文件变更信号，
     * 其余构造依赖与本路径无关，一律 relaxed mock。
     */
    private fun newFileBrowseViewModel(
        fileAccess: FileAccessProvider,
        fileChangeHub: FileChangeHub = mockk { every { watchWorkspace(any()) } returns emptyFlow() }
    ): AIAgentViewModel = AIAgentViewModel(
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
        sessionUseCase = mockk(relaxed = true) {
            coEvery { getMostRecentSessionOfWorkspace(any()) } coAnswers { awaitCancellation() }
        },
        messagePersistenceUseCase = mockk(relaxed = true),
        agentChatRequestBus = mockk(relaxed = true) {
            coEvery { receiveOrNull(any()) } coAnswers { awaitCancellation() }
        },
        agentRunStateStore = mockk(relaxed = true),
        planApprovalManager = mockk(relaxed = true),
        terminalSessionManager = mockk(relaxed = true) { every { tabFinishedEvents } returns MutableSharedFlow() },
        remoteTerminalSessionManager = mockk(relaxed = true),
        // 后台命令结束事件流：与 terminalSessionManager 同样桩成空流，本路径不依赖它。
        terminalSessionProvider = mockk(relaxed = true) { every { tabFinishedEvents } returns MutableSharedFlow() },
        slashCommandRegistry = mockk {
            // init 里 _currentWorkspace.collect 会立刻触发 refresh()：真实实现会走 IO 调度器
            // 真扫技能目录（GlobalDirectorySkillSource → File.walkTopDown）并在 FileLogger 的
            // 真后台线程上落盘——异常一旦逃逸就会挂在同 JVM 随后开跑的 runTest 上（CI 上
            // GitRepositoryTest 的 UncaughtExceptionsBeforeTest 即此因）。stub 掉，让 init 的
            // collect 只验证“收到工作区变化”这件事本身。
            coEvery { refresh() } just runs
        },
        fileMentionRepository = mockk(relaxed = true),
        checkpointManager = mockk(relaxed = true),
        checkpointDao = mockk(relaxed = true),
        backupManager = mockk(relaxed = true),
        mcpManager = mockk(relaxed = true),
        agentSoundSettings = mockk(relaxed = true),
        generalSettingsRepository = mockk(relaxed = true),
        keepaliveSettings = mockk(relaxed = true),
        subAgentEventBus = mockk(relaxed = true) { every { events } returns MutableSharedFlow() },
        agentNotificationCenter = mockk(relaxed = true),
        agentDefinitionRepository = mockk(relaxed = true),
        todoItemDao = mockk(relaxed = true),
        fileAccess = fileAccess,
        fileChangeHub = fileChangeHub,
        workspaceWriteSignal = writeSignal,
        contextUsageHolder = mockk(relaxed = true),
        // 容器根行依赖这两个流：桩成会发值，否则 browseScope 永不产出、browseState 也就永不发帧。
        containerSettingsRepository = mockk(relaxed = true) {
            every { activeProfileFlow } returns profile
        },
        containerOsDetector = mockk(relaxed = true) { every { osMap } returns MutableStateFlow(emptyMap()) },
        liveNotificationSettings = mockk(relaxed = true) { every { enabledFlow } returns MutableStateFlow(false) },
        agentLiveNotificationManager = mockk(relaxed = true),
        approvalNotificationSettings = mockk(relaxed = true) { every { enabledFlow } returns MutableStateFlow(false) },
        agentApprovalNotificationManager = mockk(relaxed = true),
        context = mockk(relaxed = true) {
            every { getSharedPreferences(any(), any()).getStringSet(any(), any()) } returns null
        }
    )
}
