package com.aicove.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicove.feature.agent.presentation.AIAgentViewModel
import com.aicove.feature.agent.presentation.AgentUIMessage

/**
 * item 渲染所需的共享依赖，一次性打包传入。
 *
 * 标 [Stable] 而非不可变：字段全为 val、实例本身不换，但 [reasoningDurations] 是 ViewModel 的
 * SnapshotStateMap，内容会变（组合期读它即订阅）。依赖未变时由 remember 复用同一实例，
 * 顶层 [RenderChatItem] 才能作为可跳过组真的跳过重组。
 * 原先它写作 [AIChatPanel] 内的局部函数，编译器不为局部函数生成跳过组，
 * 父级任何一次重组都会把全部可见 item 重跑一遍（含 Markdown 解析）。
 */
@Stable
internal class ChatItemEnv(
    val viewModel: AIAgentViewModel,
    val markdownCache: MarkdownRenderCache,
    val taskDurations: Map<String, Long>,
    val reasoningDurations: Map<String, Long>,
    val turnUsages: Map<String, TurnUsage>,
    val actionableMessageIds: Set<String>,
    val toolGroupOverrides: Map<String, Boolean>,
    val reasoningOverrides: Map<String, Boolean>,
    val messageEntryDelays: Map<String, Long>,
    val triggeredSkills: Map<String, List<String>>,
    val runningToolIds: Set<String>,
    /** 实时工具输出：messageId → 当前已产出的输出文本，一次查表代替逐项扫描。 */
    val liveOutputs: Map<String, String>,
    val onToolItemToggled: () -> Unit,
    val onOpenMessageMenu: (AgentUIMessage) -> Unit
)

// 渲染一条 item。轮头与工具分组的展开内容都由 [flattenChatProcesses] 拆成独立列表 item，
// 这里只画折叠头本身：分组头 item 再画一遍成员，就是同一个工具两行（见 [ChatRenderItem.toolGroup]）。
@Composable
internal fun RenderChatItem(item: ChatRenderItem, env: ChatItemEnv) {
    val header = item.turnHeader
    val group = item.toolGroup
    when {
        header != null -> {
            // 运行中且无过程项（如轮首用户行还没落库/被翻出窗口）：纯状态行不可点——
            // 点开是空的 AnimatedVisibility，用户会以为「卡住了、点不开」。
            val clickable = !header.running || header.processCount > 0
            TurnCollapseHeader(
                running = header.running,
                durationMs = header.endMessageId?.let { env.taskDurations[it] },
                expanded = header.expanded,
                processCount = header.processCount,
                enabled = clickable,
                onToggle = {
                    env.viewModel.setTurnExpanded(header.key, !header.expanded)
                    env.onToolItemToggled()
                }
            )
            AnimatedVisibility(
                visible = header.expanded,
                // 只做绘制期动画（仅 alpha）：这个展开区在 LazyColumn 的 item 里，
                // 高度动画会逐帧改 item 高度 → 下方内容整体位移 → 滑动时看到内容被推着走。
                // 特别地，AI 收工那一刻本轮 running 由 true 变 false，轮头自动收起，
                // 若带高度动画就会在用户滑动中途突然塌缩一大块。
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    item.turnProcess.forEach { child -> RenderChatItem(child, env) }
                }
            }
        }
        item.contentType == REASONING_CONTENT_TYPE -> {
            // 助手的思考抽为过程项：展开态交给宿主持久化，划出视口/切页返回仍保持。
            ReasoningBubble(
                text = item.message.reasoning.orEmpty(),
                cache = env.markdownCache,
                durationMs = env.reasoningDurations[item.message.id],
                expandedOverride = env.reasoningOverrides[item.message.id],
                onExpandedChange = { env.viewModel.setReasoningExpanded(item.message.id, it) }
            )
        }
        group != null -> {
            ToolCallGroupHeader(
                count = group.size,
                running = group.any { it.id in env.runningToolIds || it.isToolRunning(null) },
                expanded = item.groupExpanded,
                onToggle = {
                    env.viewModel.setToolExpanded(item.key, !item.groupExpanded)
                    env.onToolItemToggled()
                }
            )
        }
        else -> {
            val message = item.message
            AgentMessageItem(
                message = message,
                showActions = message.id in env.actionableMessageIds,
                liveOutput = env.liveOutputs[message.id],
                markdownCache = env.markdownCache,
                contentSlice = item.slice,
                isChunkHeader = item.isChunkHeader,
                isChunkFooter = item.isChunkFooter,
                reasoningVisible = item.reasoningVisible,
                onRewindClick = { env.viewModel.openRewindMenu(it) },
                onMoreClick = { env.onOpenMessageMenu(it) },
                toolExpandedOverride = env.toolGroupOverrides[message.id],
                onToolExpandedChange = { isExpanded -> env.viewModel.setToolExpanded(message.id, isExpanded) },
                onToolToggle = env.onToolItemToggled,
                // 分组展开后的成员行内缩一级，与分组头区分层级（成员行是独立 item，见 [flattenChatProcesses]）
                contentPadding = if (item.groupMember) ToolGroupMemberPadding else PaddingValues(0.dp),
                reasoningDurationMs = env.reasoningDurations[message.id],
                taskDurationMs = env.taskDurations[message.id],
                turnUsage = env.turnUsages[message.id],
                entryDelayMs = env.messageEntryDelays[message.id],
                triggeredSkills = env.triggeredSkills[message.id].orEmpty()
            )
        }
    }
}
