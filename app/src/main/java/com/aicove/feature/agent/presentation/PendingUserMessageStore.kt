package com.aicove.feature.agent.presentation

import androidx.compose.runtime.mutableStateMapOf

internal class PendingUserMessageStore {
    private val sessions = mutableStateMapOf<String, List<PendingUserMessage>>()

    operator fun get(sessionId: String?): List<PendingUserMessage> =
        if (sessionId == null) emptyList() else sessions[sessionId].orEmpty()

    fun sending(sessionId: String, message: PendingUserMessage) {
        sessions[sessionId] = get(sessionId).filterNot { it.id == message.id } + message
    }

    fun persisted(sessionId: String, messageId: String?, before: Long) {
        val pending = sessions[sessionId] ?: return
        if (messageId == null || pending.none { it.id == messageId }) return
        sessions[sessionId] = pending.map {
            if (it.id == messageId) it.copy(persistedBefore = before) else it
        }
    }

    fun acknowledge(sessionId: String, messageIds: Set<String>) {
        val pending = sessions[sessionId] ?: return
        if (messageIds.isEmpty()) return
        val remaining = pending.filterNot { it.id in messageIds }
        if (remaining.isEmpty()) sessions.remove(sessionId)
        else if (remaining.size != pending.size) sessions[sessionId] = remaining
    }

    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun discard(requests: Collection<QueuedRequest>) {
        val ids = requests.mapNotNullTo(HashSet()) { it.clientMessageId }
        if (ids.isNotEmpty()) sessions.keys.toList().forEach { acknowledge(it, ids) }
    }
}
