package com.aicove.feature.agent.presentation.component

import com.aicove.feature.agent.presentation.AgentUIMessage

internal fun buildTruncatedTurnItems(
    messages: List<AgentUIMessage>,
    headKey: String,
    running: Boolean,
    expanded: Boolean,
    groupOverrides: Map<String, Boolean>,
): List<ChatRenderItem> {
    val resultId = if (running) null else messages.lastOrNull()?.takeIf { it.isResultCandidate() }?.id
    val items = mutableListOf<ChatRenderItem>()
    var process = mutableListOf<AgentUIMessage>()
    var processTail = mutableListOf<ChatRenderItem>()
    var headerIndex = 0

    fun flushProcess() {
        if (process.isEmpty() && processTail.isEmpty()) return
        val key = if (headerIndex == 0) "$headKey#header" else "$headKey#header#$headerIndex"
        headerIndex++
        val processItems = buildTurnProcessItems(process, null, groupOverrides) + processTail
        items += ChatRenderItem(
            message = messages.last(),
            key = key,
            contentType = TURN_HEADER_CONTENT_TYPE,
            turnHeader = TurnHeaderState(
                key = headKey,
                endMessageId = null,
                running = running,
                expanded = expanded,
                processCount = processItems.size,
            ),
            turnProcess = processItems,
        )
        process = mutableListOf()
        processTail = mutableListOf()
    }

    for (message in messages) {
        when {
            message.id == resultId -> {
                if (!message.reasoning.isNullOrEmpty()) {
                    processTail += ChatRenderItem(
                        message = message,
                        key = "${message.id}#reasoning",
                        contentType = REASONING_CONTENT_TYPE,
                    )
                }
                flushProcess()
                items += messageRenderItems(message).map { it.copy(reasoningVisible = false) }
            }
            message.isPersistentInTurn() -> {
                flushProcess()
                items += messageRenderItems(message)
            }
            else -> process += message
        }
    }
    flushProcess()
    if (!expanded) return items

    val expandedItems = mutableListOf<ChatRenderItem>()
    var itemIndex = 0
    while (itemIndex < items.size) {
        val item = items[itemIndex]
        if (item.turnHeader == null) {
            expandedItems += item
            itemIndex++
            continue
        }
        expandedItems += flattenChatProcesses(listOf(item))
        itemIndex++
    }
    return expandedItems.withUniqueKeys()
}

internal fun resolveChatItems(
    messagesReady: Boolean,
    useAsyncItems: Boolean,
    asyncItems: List<ChatRenderItem>?,
    buildSynchronously: () -> List<ChatRenderItem>,
): List<ChatRenderItem> = when {
    asyncItems != null -> asyncItems
    !messagesReady || useAsyncItems -> emptyList()
    else -> buildSynchronously()
}
