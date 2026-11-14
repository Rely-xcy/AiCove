package com.aicove.core.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Agent 生命周期事件的宿主端口。
 *
 * agent 只依赖这份 core 契约，不感知插件实现。通知方法非阻塞、异常隔离；
 * 可写 hook 使用挂起接口返回决策，改写后的参数仍须经过宿主权限检查。
 */
interface AgentLifecycleHooks {
    suspend fun beforeToolCall(
        sessionId: String?,
        runId: String,
        callId: String,
        toolName: String,
        mode: String,
        scope: String,
        agentName: String?,
        arguments: JsonObject
    ): ToolBeforeHookResult

    suspend fun afterToolCall(
        sessionId: String?,
        runId: String,
        callId: String,
        toolName: String,
        mode: String,
        scope: String,
        agentName: String?,
        arguments: JsonObject,
        result: JsonElement
    ): ToolAfterHookResult

    suspend fun reviewPermission(
        runId: String,
        sessionId: String?,
        mode: String,
        scope: String,
        agentName: String?,
        callId: String,
        toolName: String,
        arguments: JsonObject
    ): PermissionReviewHookResult

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
