package com.aicove.di

import android.content.Context
import com.aicove.core.net.applyTcpKeepAlive
import com.aicove.core.plugin.AgentLifecycleHooks
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicove.feature.agent.data.local.dao.AgentMessageDao
import com.aicove.feature.agent.data.local.dao.ChatSessionDao
import com.aicove.feature.agent.data.local.dao.CheckpointDao
import com.aicove.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicove.feature.agent.data.local.dao.TodoItemDao
import com.aicove.feature.agent.data.local.dao.SessionBriefDao
import com.aicove.feature.backup.data.local.dao.BackupTargetDao
import com.aicove.feature.settings.data.local.dao.AIProviderDao
import com.aicove.feature.settings.domain.repository.AIProviderRepository
import com.aicove.feature.agent.data.local.database.AgentDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicove.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicove.feature.agent.data.remote.gemini.GeminiApi
import com.aicove.feature.agent.data.remote.openai.OpenAIApi
import com.aicove.feature.agent.domain.container.CommandEngine
import com.aicove.feature.agent.domain.container.DelegatingCommandEngine
import com.aicove.feature.agent.domain.container.LinuxContainerEngine
import com.aicove.feature.agent.domain.container.RemoteSshConnection
import com.aicove.feature.agent.domain.container.RemoteSshEngine
import com.aicove.feature.agent.domain.container.SharedPrefsSshHostKeyStore
import com.aicove.feature.agent.domain.container.SharedPrefsSshLoginKeyStore
import com.aicove.feature.agent.domain.container.SshHostKeyStore
import com.aicove.feature.agent.domain.container.SshLoginKeyStore
import com.aicove.feature.settings.data.repository.ExecutionMode
import com.aicove.feature.settings.data.repository.ExecutionModeHolder
import com.aicove.feature.agent.domain.tool.file.ReadFileTool
import com.aicove.feature.agent.domain.tool.file.SendFileTool
import com.aicove.feature.agent.domain.tool.file.GenerateImageTool
import com.aicove.feature.agent.domain.tool.file.ViewImageTool
import com.aicove.feature.agent.domain.tool.file.WriteFileTool
import com.aicove.feature.agent.domain.tool.editor.EditFileTool
import com.aicove.feature.agent.domain.tool.container.ExecuteCommandTool
import com.aicove.feature.agent.domain.tool.container.TerminalSessionTool
import com.aicove.feature.agent.domain.tool.explorer.ListFilesTool
import com.aicove.feature.agent.domain.tool.explorer.SearchCodeTool
import com.aicove.feature.agent.domain.tool.shizuku.ShizukuTool
import com.aicove.feature.agent.domain.tool.vscreen.VsInputTool
import com.aicove.feature.agent.domain.tool.vscreen.VsLogcatTool
import com.aicove.feature.agent.domain.tool.vscreen.VsObserveTool
import com.aicove.feature.agent.domain.tool.vscreen.VsStartTool
import com.aicove.feature.agent.domain.tool.vscreen.VsStopTool
import com.aicove.feature.agent.domain.tool.skill.LoadSkillTool
import com.aicove.feature.agent.domain.tool.question.AskUserQuestionTool
import com.aicove.feature.agent.domain.tool.root.RootTool
import com.aicove.feature.agent.domain.tool.todo.TodoTool
import com.aicove.feature.agent.domain.tool.subagent.TaskTool
import com.aicove.feature.agent.domain.tool.subagent.MessageParentTool
import com.aicove.feature.agent.domain.subagent.SubAgentEventBus
import com.aicove.feature.agent.domain.prompt.SystemPromptProvider
import com.aicove.feature.agent.domain.workflow.AgentWorkflow
import com.aicove.feature.agent.domain.tool.ToolPermissionManager
import com.aicove.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicove.feature.agent.domain.tool.ToolRegistry
import com.aicove.feature.agent.domain.tool.ToolOutputStore
import com.aicove.feature.settings.data.remote.ModelMetadataService
import com.aicove.feature.terminal.domain.DelegatingTerminalSessionProvider
import com.aicove.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicove.feature.terminal.domain.TerminalSessionManager
import com.aicove.feature.terminal.domain.TerminalSessionProvider
import com.aicove.feature.workspace.domain.FileAccessProvider
import com.aicove.feature.workspace.domain.DelegatingFileAccess
import com.aicove.feature.workspace.domain.LocalFileAccess
import com.aicove.feature.workspace.domain.RemoteSftpFileAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

import com.aicove.core.db.MigrationLoader
import com.aicove.core.util.FileLogger
import com.aicove.feature.agent.domain.checkpoint.CheckpointManager
import com.aicove.feature.agent.domain.notification.AgentEventInjector
import com.aicove.feature.agent.domain.notification.AgentNotificationCenter
import com.aicove.feature.agent.domain.notification.DefaultAgentEventInjector
import com.aicove.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicove.feature.agent.domain.session.SessionUseCase
import com.aicove.feature.agent.domain.tool.mcp.ManageMcpTool
import com.aicove.feature.agent.domain.tool.memory.MemoryTool
import com.aicove.feature.agent.domain.tool.config.ConfigTool
import com.aicove.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicove.feature.agent.domain.tool.mode.PlanModeTool
import com.aicove.feature.agent.domain.tool.goal.GoalStatusTool
import com.aicove.feature.agent.domain.tool.search.WebFetchTool
import com.aicove.feature.agent.domain.tool.search.WebSearchTool
import com.aicove.feature.agent.domain.tool.browser.BrowserTool
import com.aicove.feature.agent.domain.engine.AgentEngine
import com.aicove.feature.agent.domain.schedule.WriteLeaseRegistry
import com.aicove.feature.agent.domain.subagent.InterruptedWorkRecorder
import com.aicove.feature.agent.domain.workflow.StatefulAgentWorkflow
import com.aicove.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicove.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicove.feature.settings.data.repository.GeneralSettingsRepository
import com.aicove.feature.settings.data.repository.MemorySettingsRepository
import com.aicove.feature.settings.data.repository.ProviderKeyRotator
import com.aicove.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicove.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicove.feature.workspace.data.repository.WorkspaceRepository

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase {
        return Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            AgentDatabase.DATABASE_NAME
        ).addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
    }

    @Provides
    @Singleton
    fun provideCheckpointDao(database: AgentDatabase): CheckpointDao {
        return database.checkpointDao()
    }

    @Provides
    @Singleton
    fun provideAgentMessageDao(database: AgentDatabase): AgentMessageDao {
        return database.agentMessageDao()
    }

    @Provides
    @Singleton
    fun provideChatSessionDao(database: AgentDatabase): ChatSessionDao {
        return database.chatSessionDao()
    }

    @Provides
    @Singleton
    fun provideAIProviderDao(database: AgentDatabase): AIProviderDao {
        return database.aiProviderDao()
    }

    @Provides
    @Singleton
    fun provideRemoteConnectionDao(database: AgentDatabase): RemoteConnectionDao {
        return database.remoteConnectionDao()
    }

    @Provides
    @Singleton
    fun provideLlmCallRecordDao(database: AgentDatabase): LlmCallRecordDao {
        return database.llmCallRecordDao()
    }

    @Provides
    @Singleton
    fun provideTodoItemDao(database: AgentDatabase): TodoItemDao {
        return database.todoItemDao()
    }

    @Provides
    @Singleton
    fun provideSessionBriefDao(database: AgentDatabase): SessionBriefDao {
        return database.sessionBriefDao()
    }

    @Provides
    @Singleton
    fun provideBackupTargetDao(database: AgentDatabase): BackupTargetDao {
        return database.backupTargetDao()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        // 统一 UA 为 aicode/<版本> (Android)；请求已带显式 UA（如用户自定义头）时不覆盖。
        // 版本号走 PackageManager（项目未开启 BuildConfig），dev 构建为 1.x.y-dev.N+hash 天然可溯源。
        val userAgent = runCatching {
            val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            "aicode/$name (Android)"
        }.getOrDefault("aicode (Android)")
        // 流式 SSE 下读超时是「相邻数据块之间」的等待上限，设为 0（无限制），
        // 慢生成不会因块间隔超时被掐断；首字节前的卡死由上层 watchdog（RetryPolicy）兜底。
        return OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .eventListenerFactory(EventListener.Factory { call ->
                object : EventListener() {
                    private val callId = Integer.toHexString(System.identityHashCode(call))
                    private val host = call.request().url.host
                    private var callStartNanos = 0L
                    private var connectStartNanos = 0L
                    private var connectMs: Long? = null
                    private var connectionId = "none"
                    private var protocol = "unknown"

                    /** 每次请求只在结束/失败时落一条；中间阶段仅记录状态，不落盘。 */
                    private fun logResult(stage: String, details: String = "") {
                        val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - callStartNanos)
                        FileLogger.i(
                            "OkHttp",
                            "call=$callId host=$host connection=$connectionId protocol=$protocol " +
                                "stage=$stage totalMs=$totalMs" +
                                (connectMs?.let { " connectMs=$it" } ?: "") + details
                        )
                    }

                    override fun callStart(call: Call) {
                        callStartNanos = System.nanoTime()
                    }

                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        connectStartNanos = System.nanoTime()
                        connectMs = null
                        connectionId = "none"
                        protocol = "unknown"
                    }

                    override fun connectEnd(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy,
                        protocol: Protocol?
                    ) {
                        this.protocol = protocol?.toString() ?: "unknown"
                        connectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStartNanos)
                    }

                    override fun connectFailed(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy,
                        protocol: Protocol?,
                        ioe: IOException
                    ) {
                        this.protocol = protocol?.toString() ?: "unknown"
                        connectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStartNanos)
                    }

                    override fun connectionAcquired(call: Call, connection: Connection) {
                        connectionId = Integer.toHexString(System.identityHashCode(connection))
                        protocol = connection.protocol().toString()
                        // 长连接在后台空闲时会被 NAT/路由器回收，缩短 keepalive 探测间隔以维持映射。
                        connection.socket().applyTcpKeepAlive()
                    }

                    override fun callFailed(call: Call, ioe: IOException) {
                        logResult("callFailed", " exception=${ioe.javaClass.name}")
                    }

                    override fun callEnd(call: Call) {
                        logResult("callEnd")
                    }
                }
            })
            .addInterceptor { chain ->
                val request = chain.request()
                val finalRequest = if (request.header("User-Agent") != null) {
                    request
                } else {
                    request.newBuilder().header("User-Agent", userAgent).build()
                }
                chain.proceed(finalRequest)
            }
            .build()
    }

    @Provides
    @Singleton
    @Named("OpenAI")
    fun provideOpenAIRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("Anthropic")
    fun provideAnthropicRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAIApi(@Named("OpenAI") retrofit: Retrofit): OpenAIApi {
        return retrofit.create(OpenAIApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAnthropicApi(@Named("Anthropic") retrofit: Retrofit): AnthropicApi {
        return retrofit.create(AnthropicApi::class.java)
    }

    @Provides
    @Singleton
    @Named("Gemini")
    fun provideGeminiRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(@Named("Gemini") retrofit: Retrofit): GeminiApi {
        return retrofit.create(GeminiApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCommandEngine(delegate: DelegatingCommandEngine): CommandEngine = delegate

    @Provides
    @Singleton
    fun provideFileAccess(delegate: DelegatingFileAccess): FileAccessProvider = delegate

    @Provides
    @Singleton
    fun provideTerminalSessionProvider(delegate: DelegatingTerminalSessionProvider): TerminalSessionProvider = delegate

    @Provides
    @Singleton
    fun provideDelegatingTerminalSessionProvider(
        modeHolder: ExecutionModeHolder,
        local: TerminalSessionManager,
        remote: RemoteTerminalSessionManager
    ): DelegatingTerminalSessionProvider = DelegatingTerminalSessionProvider(modeHolder, local, remote)

    @Provides
    @Singleton
    fun provideRemoteSftpFileAccess(
        connection: RemoteSshConnection,
        workspaceRepository: WorkspaceRepository,
        @ApplicationContext context: Context
    ): RemoteSftpFileAccess = RemoteSftpFileAccess(connection, workspaceRepository, context)

    @Provides
    @Singleton
    fun provideSshHostKeyStore(impl: SharedPrefsSshHostKeyStore): SshHostKeyStore = impl

    @Provides
    @Singleton
    fun provideSshLoginKeyStore(impl: SharedPrefsSshLoginKeyStore): SshLoginKeyStore = impl

    @Provides
    @Singleton
    fun provideToolRegistry(
        readFileTool: ReadFileTool,
        sendFileTool: SendFileTool,
        viewImageTool: ViewImageTool,
        writeFileTool: WriteFileTool,
        editFileTool: EditFileTool,
        generateImageTool: GenerateImageTool,
        executeCommandTool: ExecuteCommandTool,
        shizukuTool: ShizukuTool,
        terminalSessionTool: TerminalSessionTool,
        listFilesTool: ListFilesTool,
        searchCodeTool: SearchCodeTool,
        loadSkillTool: LoadSkillTool,
        askUserQuestionTool: AskUserQuestionTool,
        manageMcpTool: ManageMcpTool,
        webSearchTool: WebSearchTool,
        webFetchTool: WebFetchTool,
        planModeTool: PlanModeTool,
        goalStatusTool: GoalStatusTool,
        todoTool: TodoTool,
        memoryTool: MemoryTool,
        configTool: ConfigTool,
        taskTool: TaskTool,
        messageParentTool: MessageParentTool,
        browserTool: BrowserTool,
        rootTool: RootTool,
        vsStartTool: VsStartTool,
        vsStopTool: VsStopTool,
        vsInputTool: VsInputTool,
        vsObserveTool: VsObserveTool,
        vsLogcatTool: VsLogcatTool
    ): ToolRegistry {
        return ToolRegistry().apply {
            register("readFile", readFileTool)
            register("sendFile", sendFileTool)
            register("viewImage", viewImageTool)
            register("writeFile", writeFileTool)
            register("editFile", editFileTool)
            register("generateImage", generateImageTool)
            register("Bash", executeCommandTool)
            register("Shizuku", shizukuTool)
            register("Root", rootTool)
            register("terminal", terminalSessionTool)
            register("list", listFilesTool)
            register("search", searchCodeTool)
            register("loadSkill", loadSkillTool)
            register("askUserQuestion", askUserQuestionTool)
            register("manageMcp", manageMcpTool)
            register("websearch", webSearchTool)
            register("webfetch", webFetchTool)
            register("planMode", planModeTool)
            register("goalStatus", goalStatusTool)
            register("todo", todoTool)
            register("memory", memoryTool)
            register("config", configTool)
            register("task", taskTool)
            register("messageParent", messageParentTool)
            register("browser", browserTool)
            register("vscreen_start", vsStartTool)
            register("vscreen_stop", vsStopTool)
            register("vscreen_input", vsInputTool)
            register("vscreen_observe", vsObserveTool)
            register("vscreen_logcat", vsLogcatTool)
        }
    }

    @Provides
    @Singleton
    fun provideAgentEventInjector(): AgentEventInjector = DefaultAgentEventInjector()

    @Provides
    @Singleton
    fun provideAgentWorkflow(
        aiProviderRepository: AIProviderRepository,
        openAIApi: OpenAIApi,
        anthropicApi: AnthropicApi,
        geminiApi: GeminiApi,
        promptProvider: SystemPromptProvider,
        permissionManager: ToolPermissionManager,
        policyEngine: ToolPermissionPolicyEngine,
        agentEngine: AgentEngine,
        planApprovalManager: PlanApprovalManager,
        toolOutputStore: ToolOutputStore,
        modelMetadataService: ModelMetadataService,
        compactionModelSettingsRepository: CompactionModelSettingsRepository,
        titleModelSettingsRepository: TitleModelSettingsRepository,
        defaultModelSettingsRepository: DefaultModelSettingsRepository,
        generalSettingsRepository: GeneralSettingsRepository,
        memorySettingsRepository: MemorySettingsRepository,
        sessionUseCase: SessionUseCase,
        messagePersistenceUseCase: MessagePersistenceUseCase,
        checkpointManager: CheckpointManager,
        llmCallRecordDao: LlmCallRecordDao,
        keyRotator: ProviderKeyRotator,
        agentNotificationCenter: AgentNotificationCenter,
        eventInjector: AgentEventInjector,
        fileAccess: FileAccessProvider,
        writeLeaseRegistry: WriteLeaseRegistry,
        interruptedWorkRecorder: InterruptedWorkRecorder,
        agentLifecycleHooks: AgentLifecycleHooks,
        @ApplicationContext context: Context
    ): AgentWorkflow {
        return StatefulAgentWorkflow(
            aiProviderRepository,
            openAIApi,
            anthropicApi,
            geminiApi,
            promptProvider,
            permissionManager,
            policyEngine,
            agentEngine,
            planApprovalManager,
            toolOutputStore,
            modelMetadataService,
            compactionModelSettingsRepository,
            titleModelSettingsRepository,
            defaultModelSettingsRepository,
            generalSettingsRepository,
            memorySettingsRepository,
            sessionUseCase,
            messagePersistenceUseCase,
            checkpointManager,
            llmCallRecordDao,
            keyRotator,
            agentNotificationCenter,
            eventInjector,
            fileAccess,
            writeLeaseRegistry,
            interruptedWorkRecorder,
            agentLifecycleHooks,
            context
        )
    }
}
