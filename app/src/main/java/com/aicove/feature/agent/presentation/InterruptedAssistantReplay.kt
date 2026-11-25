package com.aicove.feature.agent.presentation

import com.aicove.core.util.FileLogger
import com.aicove.feature.agent.data.local.dao.AgentMessageDao
import com.aicove.feature.agent.data.local.entity.AgentMessageEntity
import com.aicove.feature.agent.domain.engine.AgentEngine
import com.aicove.feature.agent.domain.engine.EngineContext
import com.aicove.feature.agent.domain.model.AgentContext
import com.aicove.feature.agent.domain.model.AgentImage
import com.aicove.feature.agent.domain.model.AgentMessage
import com.aicove.feature.agent.domain.model.AgentMode
import com.aicove.feature.agent.domain.model.GoalStatus
import com.aicove.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicove.feature.agent.domain.session.SessionUseCase
import com.aicove.feature.agent.domain.subagent.AgentDefinition
import com.aicove.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicove.feature.agent.domain.tool.AgentTool
import com.aicove.feature.agent.domain.tool.ToolRegistry
import com.aicove.feature.agent.domain.tool.vscreen.VSCREEN_TOOL_PREFIX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class InterruptedAssistantReplay(
    private val messageDao: AgentMessageDao,
    private val messagePersistence: MessagePersistenceUseCase
) {
    internal sealed interface Preparation {
        data object Ignored : Preparation
        data object Unavailable : Preparation
        data class Ready(val request: ReplayRequest) : Preparation
    }

    internal data class ReplayRequest(
        val sessionId: String,
        val projectRoot: String,
        val request: String,
        val modelRequest: String,
        val inputImages: List<AgentImage>,
        val userMessageId: String
    )

    internal data class ExecutionHistory(
        val history: List<AgentMessage>,
        val requestHistory: List<AgentMessage>,
        val replayUserMessage: AgentMessage.UserMessage?
    )

    internal data class WorkflowContext(
        val agentContext: AgentContext,
        val tools: List<AgentTool>,
        val mode: AgentMode,
        val isSubAgent: Boolean,
        val goalActive: Boolean
    )

    suspend fun prepare(
        assistant: AgentMessageEntity,
        resolveWorkspacePath: suspend (String) -> String?,
        isSlashCommand: (String) -> Boolean
    ): Preparation {
        if (assistant.role != MessageRole.ASSISTANT.name || !assistant.isError) return Preparation.Ignored

        val sessionId = assistant.sessionId
        val rows = messageDao.getMessagesBySessionOnce(sessionId)
        val lastConversationRow = rows.lastOrNull {
            !it.isCompacted && !it.isInterjection && !it.isContextExcluded &&
                !it.isCompactionMarker && !it.isContextSummary && !it.isCompactionFailureRow() &&
                !(it.role == MessageRole.USER.name && it.content.startsWith(BACKGROUND_NOTIFICATION_PREFIX))
        }
        if (lastConversationRow?.id != assistant.id) return Preparation.Ignored

        val assistantIndex = rows.indexOfLast { it.id == assistant.id }
        val userRow = rows.asSequence()
            .takeWhile { it.id != assistant.id }
            .lastOrNull {
                it.role == MessageRole.USER.name && !it.isCompacted &&
                    !it.isInterjection && !it.isContextExcluded &&
                    !it.content.startsWith(BACKGROUND_NOTIFICATION_PREFIX)
            }
        val projectRoot = resolveWorkspacePath(sessionId) ?: return Preparation.Ignored
        if (userRow == null) return Preparation.Unavailable
        val userIndex = rows.indexOfLast { it.id == userRow.id }
        if ((userIndex + 1 until assistantIndex).any { index ->
                val row = rows[index]
                !row.isCompacted && !row.isContextExcluded &&
                    !row.isCompactionMarker && !row.isContextSummary && !row.isCompactionFailureRow() &&
                    (row.role == MessageRole.TOOL.name || row.role == MessageRole.ASSISTANT.name)
            }
        ) {
            return Preparation.Unavailable
        }
        if (isSlashCommand(userRow.content)) return Preparation.Unavailable

        val history = messagePersistence.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
        val userMessage = history.firstOrNull { it.id == userRow.id } as? AgentMessage.UserMessage
            ?: return Preparation.Unavailable

        messageDao.insert(assistant.copy(isContextExcluded = true))
        messagePersistence.evictHistory(sessionId)
        return Preparation.Ready(
            ReplayRequest(
                sessionId = sessionId,
                projectRoot = projectRoot,
                request = userRow.content,
                modelRequest = userMessage.content,
                inputImages = userMessage.images,
                userMessageId = userMessage.id
            )
        )
    }

    /**
     * 全量重建历史（读整个会话 + JSON 解码工具快照 + 图片 base64）是重活且会随会话变长变慢，
     * 留在主线程上会把这一帧的消息回显与滚动一起拖住。它是 suspend，直接切到 IO，主线程只等结果。
     */
    suspend fun buildExecutionHistory(
        sessionId: String,
        replayUserMessageId: String?,
        requestChars: Int,
        attachmentCount: Int
    ): ExecutionHistory? {
        // 必须在插入本次用户消息之前读取历史：workflow 会自己 add(userRequest)，避免重复。
        val historyStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(
            "AIAgentViewModel",
            "history.start",
            details = "operation=$historyStartedNs requestChars=$requestChars attachments=$attachmentCount"
        )
        val history = messagePersistence.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
        FileLogger.memoryCheckpoint(
            "AIAgentViewModel",
            "history.ready",
            elapsedMs = (System.nanoTime() - historyStartedNs) / 1_000_000,
            details = "operation=$historyStartedNs messages=${history.size}"
        )

        val replayIndex = replayUserMessageId?.let { id -> history.indexOfFirst { it.id == id } } ?: -1
        val replayUserMessage = if (replayUserMessageId != null) {
            history.getOrNull(replayIndex) as? AgentMessage.UserMessage
        } else {
            null
        }
        if (replayUserMessageId != null && replayUserMessage == null) return null
        val requestHistory = if (replayUserMessage != null) history.take(replayIndex) else history
        return ExecutionHistory(history, requestHistory, replayUserMessage)
    }

    /**
     * 正式流为本轮准备会话模式、子代理范围、回放上下文与可用工具。
     *
     * 「是不是子代理会话」只在这里判一次：结构性事实（会话行有 parentId），下面的所有入口都读它。
     * 以前各处分别拿「有没有自定义定义」近似，默认子代理（没有同名定义）就被当成主对话了。
     * 子会话的 subagentType 存的是自定义 agent 名；能查到定义时提示词与工具集都按它组装。
     * 定义是配置性事实，与子代理判定无关，不能拿它兼任。
     * 目录扫描（远程模式经 SFTP）切到 IO，避免阻塞主线程。
     */
    suspend fun prepareWorkflowContext(
        sessionId: String,
        projectRoot: String,
        currentFile: String?,
        selectedCode: String?,
        history: List<AgentMessage>,
        requestHistory: List<AgentMessage>,
        inputImages: List<AgentImage>,
        userMessageId: String?,
        skillInjection: String?,
        sessionUseCase: SessionUseCase,
        agentEngine: AgentEngine,
        toolRegistry: ToolRegistry,
        agentDefinitionRepository: AgentDefinitionRepository
    ): WorkflowContext {
        val sessionEntity = sessionUseCase.getSessionById(sessionId)
        val sessionDomain = sessionEntity?.toDomain()
        val mode = sessionDomain?.mode ?: AgentMode.BUILD
        val isSubAgent = sessionEntity?.parentId != null
        val agentDefinition = withContext(Dispatchers.IO) {
            sessionEntity?.subagentType
                ?.takeIf { isSubAgent }
                ?.let { agentDefinitionRepository.findIncludingDisabled(it) }
        }

        // 本轮（一次用户请求 = 一次 agent 循环）的标识：同一轮的多次模型调用共用一个，
        // 发送前兜底截断据此冻结，不随两次调用之间的估算抖动换截法。
        val agentContext = AgentContext(
            currentFile = currentFile,
            selectedCode = selectedCode,
            projectRoot = projectRoot,
            language = currentFile?.let { detectLanguage(it) },
            history = requestHistory,
            inputImages = inputImages,
            sessionId = sessionId,
            userMessageId = userMessageId,
            skillInjection = skillInjection,
            turnId = java.util.UUID.randomUUID().toString(),
            mode = mode,
            modeBeforePlan = sessionDomain?.modeBeforePlan,
            goal = sessionDomain?.goal,
            goalStatus = sessionDomain?.goalStatus,
            reasoningEffort = sessionDomain?.reasoningEffort?.apiValue,
            isSubAgent = isSubAgent,
            agentDefinition = agentDefinition
        )

        val allTools = (toolRegistry.getAvailableTools() + agentEngine.tools(
            EngineContext(
                sessionId = sessionId,
                projectRoot = projectRoot,
                mode = mode,
                history = history,
                isSubAgent = isSubAgent
            )
        )).distinctBy { it.name }
        val goalActive = sessionDomain?.goalStatus == GoalStatus.ACTIVE
        val tools = when {
            agentDefinition != null -> {
                val allowed = agentDefinition.filterToolNames(allTools.map { it.name }).toSet()
                allTools.filter { it.name in allowed }
            }
            isSubAgent -> allTools.filterNot { it.name == AgentDefinition.NESTED_TOOL || it.name.startsWith(VSCREEN_TOOL_PREFIX) }
            else -> allTools.filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
        }.let { list ->
            // goal 自主循环期间禁用 PLAN（其退出门禁需人工审查）；非 goal 期间不暴露 goalStatus 工具。
            if (goalActive) list.filterNot { it.name == "planMode" }
            else list.filterNot { it.name == "goalStatus" }
        }
        return WorkflowContext(agentContext, tools, mode, isSubAgent, goalActive)
    }

    private fun detectLanguage(filePath: String): String =
        when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }

    private fun AgentMessageEntity.isCompactionFailureRow(): Boolean =
        role == MessageRole.TOOL.name && toolName == COMPACTION_FAILURE_TOOL_NAME

    fun toVisibleMessage(entity: AgentMessageEntity): AgentUIMessage =
        entity.toUIMessage().copy(
            isInterrupted = isInterruptedAssistantRole(entity.role, entity.isError),
            isContextExcluded = entity.isContextExcluded
        )
}
