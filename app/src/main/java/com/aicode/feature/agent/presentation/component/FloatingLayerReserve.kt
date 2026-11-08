package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 聊天列表底部留白的更新策略：只增不减，且延后一拍再落地。
 *
 * 背景：底栏（输入框 + 队列/待办/仪表盘面板）的高度是实测来的。底栏形态一变，
 * 悬浮层高度就变；若直接拿它去改 LazyColumn 的 contentPadding.bottom，LazyColumn 会就地
 * 重测、整体位移——用户正滑着的时候，看到的就是内容被推着走。
 *
 * 所以把「实测高度」与「列表留白」拆开：
 * - 实测高度写进 [measuredHeightPx]，由调用方在布局回调里喂进来；
 * - 留白只在这里更新，且**只允许变大**：变小会把内容整体往上拽，同样是跳动；
 * - 更新延后 [SETTLE_MS]，让这次尺寸变化先落定，再一次性更新，避免连续抖动。
 */
internal object FloatingLayerReserve {

    /** 留白更新前的等待时长（ms）：让底栏的尺寸变化先落定。 */
    const val SETTLE_MS = 120L

    /**
     * 记住当前实测到的悬浮层高度（px），并返回列表该用的底部留白（px）。
     *
     * @param measuredHeightPx 实测的悬浮层高度（px）；传 0 表示尚未测量到，用兜底值。
     * @param fallbackPx 尚未测量到时的兜底留白（px）。
     * @param gapPx 悬浮层与最后一条消息之间要额外留的间距（px）。
     */
    @Composable
    fun rememberReservePx(
        measuredHeightPx: Int,
        fallbackPx: Int,
        gapPx: Int
    ): Int {
        var reservePx by remember { mutableIntStateOf(fallbackPx) }
        LaunchedEffect(measuredHeightPx) {
            if (measuredHeightPx <= 0) return@LaunchedEffect
            val target = measuredHeightPx + gapPx
            // 只增不减：留白缩小会把内容整体往上拽，与跳动无异。
            if (target > reservePx) {
                delay(SETTLE_MS)
                reservePx = target
            }
        }
        return reservePx
    }
}
