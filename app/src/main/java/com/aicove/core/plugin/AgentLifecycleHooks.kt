package com.aicove.core.plugin

/**
 * Agent 生命周期事件的宿主端口。
 *
 * agent 只依赖这份 core 契约，不感知插件实现；实现必须是非阻塞、异常隔离的，不能影响主流程。
 * 参数只含运行元数据，不携带消息正文、工具参数或工具结果。
 */
interface AgentLifecycleHooks {
    fun onRunStarted(
        sessionId: String,
        runId: String,
        mode: String,
        scope: String,
        agentName: String?
    )

    fun onRunFinished(
        sessionId: String,
        runId: String,
        mode: String,
        scope: String,
        agentName: String?,
        status: String,
        toolCalls: Int,
        errorCode: String?
    )

    fun onRequestStarted(
        sessionId: String?,
        runId: String,
        requestId: String,
        mode: String,
        scope: String,
        agentName: String?,
        messageCount: Int,
        toolCount: Int,
        estimatedInputTokens: Int
    )

    fun onRequestFinished(
        sessionId: String?,
        runId: String,
        requestId: String,
        mode: String,
        scope: String,
        agentName: String?,
        status: String,
        inputTokens: Int,
        outputTokens: Int,
        toolCalls: Int,
        durationMs: Int,
        stopReason: String?,
        errorCode: String?
    )

    fun onToolStarted(
        sessionId: String?,
        runId: String,
        callId: String,
        toolName: String,
        mode: String,
        scope: String,
        agentName: String?
    )

    fun onToolFinished(
        sessionId: String?,
        runId: String,
        callId: String,
        toolName: String,
        mode: String,
        scope: String,
        agentName: String?,
        status: String,
        durationMs: Int
    )

    fun onCompaction(
        sessionId: String?,
        runId: String,
        mode: String,
        scope: String,
        agentName: String?,
        estimatedInputTokens: Int
    )
}
