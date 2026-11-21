package com.aicove.feature.agent.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ChatMessageWindowState(private val defaultLimit: Int = 30) {
    private val _limits = MutableStateFlow<Map<String, Int>>(emptyMap())
    val limits: StateFlow<Map<String, Int>> = _limits.asStateFlow()

    fun limitFor(sessionId: String?, snapshot: Map<String, Int> = _limits.value): Int =
        sessionId?.let(snapshot::get) ?: defaultLimit

    fun reset(sessionId: String) {
        _limits.value = _limits.value - sessionId
    }

    fun loadMore(sessionId: String) {
        val current = limitFor(sessionId)
        _limits.value = _limits.value + (sessionId to (current + (current / 2).coerceIn(30, 90)))
    }

    fun includeAtLeast(sessionId: String, needed: Int) {
        val current = limitFor(sessionId)
        if (needed > current) _limits.value = _limits.value + (sessionId to needed)
    }
}
