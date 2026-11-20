package com.aicove.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicove.core.theme.Spacing
import com.aicove.core.ui.ContentWidth
import com.aicove.feature.agent.presentation.MessageRole
import com.aicove.feature.agent.presentation.PendingUserMessage

// 后台列表可能仍是上一帧；轮头引用了 USER 消息，但只有实际气泡能接手回显。
internal fun renderedUserMessageIds(items: List<ChatRenderItem>): Set<String> = items
    .asSequence()
    .filter { it.turnHeader == null && it.toolGroup == null && it.message.role == MessageRole.USER }
    .mapTo(HashSet()) { it.message.id }

internal fun settledUserMessageIds(pending: List<PendingUserMessage>, items: List<ChatRenderItem>): Set<String> {
    if (pending.isEmpty()) return emptySet()
    val renderedIds = renderedUserMessageIds(items)
    val oldestTimestamp = items.asSequence().map { it.message }
        .filterNot { it.isContextSummary || it.isCompactionMarker || it.isBackgroundNotification }
        .minOfOrNull { it.timestamp }
    return pending.asSequence().filter {
        it.id in renderedIds || (oldestTimestamp != null && it.persistedBefore?.let { before -> before < oldestTimestamp } == true)
    }.mapTo(HashSet()) { it.id }
}

internal fun resolvePendingUserMessages(
    pending: List<PendingUserMessage>,
    renderedIds: Set<String>,
    queuedClientMessageIds: Set<String>,
    hiddenMessageIds: Set<String> = emptySet()
): List<PendingUserMessage> {
    if (pending.isEmpty()) return pending
    return pending.filterNot {
        it.id in renderedIds || it.id in queuedClientMessageIds || it.id in hiddenMessageIds
    }
}

/**
 * 待落库用户消息的气泡。外观对齐 [AgentMessageItem] 渲染落库用户气泡的那一支（同形状、同配色、
 * 同内边距、同字号行高），落库换手时气泡本身不出现视觉跳变。
 *
 * 刻意不复用 [AgentMessageItem]：
 * 1. 那条路会带出「时间 + 复制 / 回退 / 更多」操作行，而这些按钮都要按消息 id 反查库——pending 的 id
 *    在库里还不存在，点了也是空的；
 * 2. 操作行会把 item 撑高，落库那一刻高度再跳一次。
 *
 * 注意它由调用方挂在「尾巴 item」内，不参与 `chatItems`：item 数量不变，才不会牵动
 * `firstVisibleItemIndex` 的 clamp（见 AIChatPanel 里 `__active__` 的注释）。
 */
@Composable
internal fun PendingUserMessageBubble(pending: PendingUserMessage) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // 与落库用户气泡同一套宽度口径：随文字撑开，上限为消息列宽（大屏下消息列已限宽居中）。
    val maxUserBubbleWidth = remember(screenWidthDp) {
        minOf(screenWidthDp.dp, ContentWidth.readable) - Spacing.lg * 2
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        // 纯附件（图片 / 文件）发送时没有正文，此时只出附件行，不画一个空气泡。
        if (pending.text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(ChatStyle.bubbleCorner),
                color = chatUserBubbleColor(),
                modifier = Modifier.widthIn(max = maxUserBubbleWidth)
            ) {
                Text(
                    text = pending.text,
                    color = chatUserBubbleTextColor(),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                )
            }
        }
        if (pending.attachments.isNotEmpty()) {
            MessageAttachmentList(attachments = pending.attachments)
        }
    }
}
