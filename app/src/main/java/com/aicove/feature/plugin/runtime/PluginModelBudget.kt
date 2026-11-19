package com.aicove.feature.plugin.runtime

import com.aicove.feature.agent.domain.model.AgentMessage
import com.aicove.feature.agent.domain.tool.AgentTool
import com.aicove.feature.agent.domain.workflow.TokenEstimator
import com.aicove.feature.settings.domain.model.ModelContextPolicy
import com.aicove.feature.settings.domain.model.ModelMetadata
import com.google.gson.Gson

internal object PluginModelBudget {
    private val gson = Gson()

    fun estimate(systemPrompt: String, messages: List<AgentMessage>, tools: List<AgentTool>): Int {
        val total = TokenEstimator.estimateMessages(messages).toLong() +
            TokenEstimator.estimateText(systemPrompt) +
            tools.sumOf { tool ->
                TokenEstimator.estimateText(tool.name).toLong() +
                    TokenEstimator.estimateText(tool.description) +
                    TokenEstimator.estimateText(gson.toJson(tool.toJsonSchema()))
            }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun inputBudget(metadata: ModelMetadata, requestedOutput: Int?): Int {
        val context = metadata.contextTokens.takeIf { it > 0 } ?: ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
        val outputLimit = metadata.outputTokens?.takeIf { it > 0 }
        val reservedOutput = requestedOutput?.coerceAtMost(outputLimit ?: Int.MAX_VALUE) ?: outputLimit ?: 0
        val guard = context.toLong() * ModelContextPolicy.GUARD_BUDGET_PERCENT / 100
        return minOf(
            guard,
            context.toLong() - reservedOutput,
            metadata.inputTokens?.takeIf { it > 0 }?.toLong() ?: Long.MAX_VALUE
        ).coerceAtLeast(1).toInt()
    }
}
