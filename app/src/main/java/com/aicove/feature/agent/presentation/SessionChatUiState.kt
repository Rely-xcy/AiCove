package com.aicove.feature.agent.presentation

internal fun AIAgentViewModel.clearSessionChatUiState(sessionId: String) {
    val messageTurnKeys = turnMessageKeys.remove(sessionId).orEmpty()
    messageTurnKeys.forEach { (messageId, turnKey) ->
        toolExpansionOverrides.remove(messageId)
        toolExpansionOverrides.remove("toolgroup:$messageId")
        reasoningExpansionOverrides.remove(messageId)
        turnExpansionOverrides.remove(turnKey)
    }
    reasoningExpansionOverrides.remove(streamingReasoningKey(sessionId))
}
