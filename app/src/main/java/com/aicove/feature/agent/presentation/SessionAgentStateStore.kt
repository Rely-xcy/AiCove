package com.aicove.feature.agent.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class SessionAgentStateStore {
    private val mutableStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    operator fun get(sessionId: String): AgentUIState = states.value[sessionId] ?: AgentUIState.Idle

    fun set(sessionId: String, state: AgentUIState) {
        mutableStates.update { it + (sessionId to state) }
    }

    fun prepare(sessionId: String, job: Job, describeError: (Throwable) -> String) {
        val preparing = AgentUIState.Preparing()
        set(sessionId, preparing)
        job.invokeOnCompletion { cause ->
            mutableStates.update { current ->
                // 旧轮次可能在接替任务启动后才完成，只能清理由自己发布的准备态。
                if (current[sessionId] !== preparing) current
                else current + (sessionId to if (cause != null && cause !is CancellationException) {
                    AgentUIState.Error(describeError(cause))
                } else AgentUIState.Idle)
            }
        }
    }

    fun reset() {
        mutableStates.update { states -> states.mapValues { AgentUIState.Idle } }
    }

    fun remove(sessionId: String) {
        mutableStates.update { it - sessionId }
    }
}
