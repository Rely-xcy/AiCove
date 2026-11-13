package com.aicove.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicove.R
import com.aicove.core.theme.Radius
import com.aicove.core.theme.Spacing
import com.aicove.core.theme.semanticColors
import com.aicove.core.ui.InlineBanner
import com.aicove.core.ui.InlineBannerLevel
import com.aicove.feature.agent.presentation.SubAgentCardState
import com.aicove.feature.agent.presentation.SubAgentCardStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cpu
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * `task` 工具的专属卡片宿主：操作用 CompositionLocal 下发，避免参数一路穿过
 * 消息列表、分组与气泡三层组合。
 */
data class SubAgentCardHost(
    val states: Map<String, SubAgentCardState> = emptyMap(),
    val onOpen: (String) -> Unit = {},
    val onStop: (String) -> Unit = {}
)

val LocalSubAgentCardHost = staticCompositionLocalOf<SubAgentCardHost?> { null }

/**
 * 从 `task(create)` 的工具参数与结果里解析出的派发信息（支持单个与批量派发）。
 */
internal data class ParsedSubAgentDispatch(
    val items: List<ParsedSubAgentDispatchItem>,
    val globalWarning: String? = null
) {
    // 兼容单项快捷访问
    val subSessionId: String? get() = items.firstOrNull()?.subSessionId
    val agentName: String? get() = items.firstOrNull()?.agentName
    val description: String get() = items.firstOrNull()?.description.orEmpty()
    val tier: String? get() = items.firstOrNull()?.tier
    val model: String? get() = items.firstOrNull()?.model
    val warning: String? get() = items.firstOrNull()?.warning ?: globalWarning
}

internal data class ParsedSubAgentDispatchItem(
    val subSessionId: String?,
    val agentName: String?,
    val description: String,
    val tier: String?,
    val model: String?,
    val warning: String?
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * 解析 `task(create)`：支持单个与批量任务派发的双重解析。
 *
 * 结果里 `data` 既可能是对象也可能是被序列化成字符串的对象；
 * 解析不出 id 时卡片仍按参数渲染，只是不能跳转。
 */
internal fun parseSubAgentDispatch(argsJson: String?, content: String?): ParsedSubAgentDispatch? {
    val args = argsJson?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
    val action = (args?.get("action") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "create"
    if (action != "create") return null

    val result = content?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
    val status = (result?.get("status") as? JsonPrimitive)?.contentOrNull
    if (status == "error") return null

    val data = result?.get("data").let { element ->
        when (element) {
            is JsonObject -> element
            is JsonPrimitive -> runCatching { json.parseToJsonElement(element.content).jsonObject }.getOrNull()
            else -> null
        }
    }

    // 1. 尝试从 result 的 subagents 数组解析批量结果
    val resultSubagents = (data?.get("subagents") as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        ParsedSubAgentDispatchItem(
            subSessionId = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            agentName = (obj["agent"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            description = (obj["description"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
            tier = (obj["tier"] as? JsonPrimitive)?.contentOrNull,
            model = (obj["model"] as? JsonPrimitive)?.contentOrNull,
            warning = (obj["warning"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        )
    }

    // 2. 尝试从 args 的 tasks 数组解析任务入参
    val argTasks = (args?.get("tasks") as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
        val desc = (obj["description"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: prompt?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        ParsedSubAgentDispatchItem(
            subSessionId = null,
            agentName = (obj["agent"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            description = desc,
            tier = (obj["tier"] as? JsonPrimitive)?.contentOrNull,
            model = null,
            warning = null
        )
    }

    val items: List<ParsedSubAgentDispatchItem> = when {
        !resultSubagents.isNullOrEmpty() -> {
            resultSubagents.mapIndexed { idx, item ->
                val fallbackDesc = argTasks?.getOrNull(idx)?.description
                if (item.description.isBlank() && !fallbackDesc.isNullOrBlank()) {
                    item.copy(description = fallbackDesc)
                } else {
                    item
                }
            }
        }
        !argTasks.isNullOrEmpty() -> argTasks
        else -> {
            // 单个子代理处理
            val agentName = (data?.get("agent") as? JsonPrimitive)?.contentOrNull
                ?: (args?.get("agent") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val description = (args?.get("description") as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: (args?.get("prompt") as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.lineSequence()?.firstOrNull()?.trim().orEmpty()

            val singleItem = ParsedSubAgentDispatchItem(
                subSessionId = (data?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                agentName = agentName,
                description = description,
                tier = (data?.get("tier") as? JsonPrimitive)?.contentOrNull,
                model = (data?.get("model") as? JsonPrimitive)?.contentOrNull,
                warning = (data?.get("warning") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            )
            listOf(singleItem)
        }
    }

    if (items.isEmpty()) return null

    return ParsedSubAgentDispatch(
        items = items,
        globalWarning = (data?.get("warning") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    )
}

/**
 * 子代理派发卡片：一张卡承载全部子任务，任务之间用细分割线分开。
 *
 * 每条任务：状态色点 + 代理名 + 状态文字，下面是任务描述；已结束时用左侧竖线
 * 引出结果摘要，再跟一行档位/改动文件数。点击任务行进入对应子会话。
 * 单项派发不重复渲染标题行。
 */
@Composable
internal fun SubAgentDispatchCard(dispatch: ParsedSubAgentDispatch) {
    val host = LocalSubAgentCardHost.current
    val items = dispatch.items
    if (items.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        dispatch.globalWarning?.takeIf { items.size > 1 }?.let { warning ->
            SubAgentWarningBanner(warning)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.mdLarge),
            color = MaterialTheme.semanticColors.cardSurface,
            border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder)
        ) {
            Column {
                if (items.size > 1) {
                    SubAgentDispatchSummary(count = items.size)
                }
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = Spacing.md),
                            thickness = 1.dp,
                            color = MaterialTheme.semanticColors.subtleBorder.copy(alpha = 0.7f)
                        )
                    }
                    SubAgentDispatchItem(
                        item = item,
                        liveState = item.subSessionId?.let { host?.states?.get(it) },
                        onOpen = { sessionId -> host?.onOpen?.invoke(sessionId) }
                    )
                }
            }
        }
    }
}

/** 批量派发的标题行：小图标 + 标题 + 尾部任务数，不再占用一整块药丸。 */
@Composable
private fun SubAgentDispatchSummary(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = FeatherIcons.Cpu,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(Spacing.xs + 2.dp))
        Text(
            text = stringResource(R.string.subagent_dispatch_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.subagent_dispatch_count, count),
            color = MaterialTheme.semanticColors.subtleText,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * 单条子代理任务。
 *
 * [liveState] 来自 ViewModel 的实时状态（按子会话 id 索引）：本进程内派发的子代理会随
 * 生命周期刷新状态，并展示结果摘要；为 null（历史消息回放、进程重启后）时只显示「已派发」，
 * 不会渲染假的「运行中」。
 */
@Composable
private fun SubAgentDispatchItem(
    item: ParsedSubAgentDispatchItem,
    liveState: SubAgentCardState?,
    onOpen: (String) -> Unit
) {
    val sessionId = item.subSessionId
    val statusColor = subAgentStatusColor(liveState?.status)
    val meta = listOfNotNull(
        modelBadge(item.tier, item.model),
        liveState?.changedFiles?.takeIf { it.isNotEmpty() }
            ?.let { files -> stringResource(R.string.subagent_card_changed_files, files.size) },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (sessionId != null) {
                    Modifier.clickable { onOpen(sessionId) }
                } else Modifier
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(Spacing.xs + 2.dp))
            Text(
                text = item.agentName ?: stringResource(R.string.subagent_card_default_agent),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = subAgentStatusLabel(liveState?.status),
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
            if (sessionId != null) {
                Spacer(Modifier.width(Spacing.xs / 2))
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = stringResource(R.string.subagent_card_open),
                    tint = MaterialTheme.semanticColors.subtleText.copy(alpha = 0.7f),
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        if (item.description.isNotBlank()) {
            Text(
                text = item.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 结果与「任务描述」分开：左侧细竖线表示这是产出，不是又一条指令。
        liveState?.excerpt?.takeIf { it.isNotBlank() }?.let { excerpt ->
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .padding(top = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(statusColor.copy(alpha = 0.45f))
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = excerpt,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (meta.isNotEmpty()) {
            Text(
                text = meta.joinToString(" · "),
                color = MaterialTheme.semanticColors.subtleText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        item.warning?.let { warning ->
            SubAgentWarningBanner(warning)
        }
    }
}

/** 警告/回退提示条。 */
@Composable
private fun SubAgentWarningBanner(warning: String) {
    InlineBanner(
        text = warning,
        level = InlineBannerLevel.Warning,
        icon = FeatherIcons.AlertCircle,
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
        textStyle = MaterialTheme.typography.labelSmall,
        maxLines = 2
    )
}

/** 状态色：运行中走品牌色，完成走语义成功色，失败走错误色，其余收敛为次级文字色。 */
@Composable
private fun subAgentStatusColor(status: SubAgentCardStatus?): Color = when (status) {
    null -> MaterialTheme.semanticColors.subtleText
    SubAgentCardStatus.RUNNING -> MaterialTheme.colorScheme.primary
    SubAgentCardStatus.COMPLETED -> MaterialTheme.semanticColors.success
    SubAgentCardStatus.FAILED -> MaterialTheme.colorScheme.error
    SubAgentCardStatus.STOPPED -> MaterialTheme.semanticColors.subtleText
}

/**
 * 状态文字。[status] 为 null——历史消息回放、进程重启后拿不到实时状态——
 * 退回静态「已派发」，不渲染可能失真的「运行中」。
 */
@Composable
private fun subAgentStatusLabel(status: SubAgentCardStatus?): String = when (status) {
    null -> stringResource(R.string.subagent_card_status_dispatched)
    SubAgentCardStatus.RUNNING -> stringResource(R.string.subagent_card_status_running)
    SubAgentCardStatus.COMPLETED -> stringResource(R.string.subagent_card_status_completed)
    SubAgentCardStatus.FAILED -> stringResource(R.string.subagent_card_status_failed)
    SubAgentCardStatus.STOPPED -> stringResource(R.string.subagent_card_status_stopped)
}

/** 档位 + 模型：档位是「轻量/均衡/强力」，模型是实际落地的 model id；inherit 时不显示模型。 */
@Composable
private fun modelBadge(tier: String?, model: String?): String? {
    val tierStr = tierLabel(tier)
    val modelStr = model?.takeIf { it.isNotBlank() && it != "inherit" }
    return when {
        tierStr != null && modelStr != null -> "$tierStr · $modelStr"
        tierStr != null -> tierStr
        modelStr != null -> modelStr
        else -> null
    }
}

@Composable
private fun tierLabel(tier: String?): String? = when (tier?.lowercase()) {
    "light" -> stringResource(R.string.settings_subagent_tier_light)
    "standard" -> stringResource(R.string.settings_subagent_tier_standard)
    "strong" -> stringResource(R.string.settings_subagent_tier_strong)
    else -> null
}
