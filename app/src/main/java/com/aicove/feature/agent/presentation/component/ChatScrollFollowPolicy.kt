package com.aicove.feature.agent.presentation.component

/**
 * 贴底校准的门槛：这一帧能不能请求「滚回锚点」。
 *
 * 三个「不要动视口」的状态缺一不可：
 * - [followBottom] 为假：用户在别处看历史，别把他拉回去；
 * - [userScrollGesture] 为真：他的手势已经产生滚动量，交给他；
 * - [pointerDown] 为真：手指已经落在列表上，**哪怕还没越过 touch slop**。
 *
 * 最后一条是补上的：手指落下到真正开始拖拽之间有一段窗口，长度 = touch slop 像素 ÷ 手指速度。
 * 快速一甩时它只有一帧，慢慢滑则是几十帧。窗口里手势标志（`userScrollGesture`/`followBottom`
 * 的置位都在「已产生滚动量」之后，拖拽标志则要越过 slop）还没建立，而流式增长照旧，
 * 逐帧校准就会把「我准备往上滑」当成「你还在底部」，每帧 `requestScrollToItem` 把视口拽回底部
 * ——表现就是慢慢滑也跳。
 *
 * 抽成纯函数是为了能用单测钉住这段窗口（本仓没有 Compose 测试设施，只能测纯逻辑）。
 */
internal fun shouldCalibrateToAnchor(
    followBottom: Boolean,
    userScrollGesture: Boolean,
    pointerDown: Boolean,
    canScrollForward: Boolean
): Boolean = followBottom && !userScrollGesture && !pointerDown && canScrollForward

/**
 * 悬浮输入层实测高度能否提交给 LazyColumn 的底部安全区。
 * 用户浏览历史或任何滚动仍在进行时必须冻结；否则 contentPadding 重测会修正锚点并造成跳动。
 */
internal fun shouldCommitInputBarReserve(
    followBottom: Boolean,
    userScrollGesture: Boolean,
    pointerDown: Boolean,
    nestedFling: Boolean,
    scrollInProgress: Boolean
): Boolean = followBottom && !userScrollGesture && !pointerDown && !nestedFling && !scrollInProgress

/**
 * 刚跑完的那一轮要不要强行保持展开。
 *
 * 运行中的轮默认展开，收工的瞬间 `expanded` 会从 true 翻成 false：这一轮的全部过程项
 * 都是顶层 LazyColumn item，一次性消失几十个。LazyColumn 只能按索引保位，正在看这一轮
 * 的用户会看到视口整体跳走。所以只在「收工那一刻用户已经离开底部」时挂住展开态；
 * 在底部收工则照常收起（校准循环会把视口带到新锚点）。
 *
 * 必须看「上一帧的 activeTurnKey」而不是「当前是否在底部」：只看后者的话，用户事后
 * 往上滑历史会让已经收起的轮重新展开，同样是一次结构突变。
 */
internal fun shouldHoldTurnExpanded(
    previousActiveTurnKey: String?,
    activeTurnKey: String?,
    followBottom: Boolean
): Boolean = previousActiveTurnKey != null && activeTurnKey == null && !followBottom

/**
 * 是否真的停在底部：最后一项的底部必须落在安全区上下界内。
 *
 * 只卡上界（`bottom <= safeBottom + tolerance`）会把「最后一项还在屏幕上」误判成在底部：
 * 用户在最后一条长消息内部往上滑时，该项仍可见且底部只会变小，条件一直成立；
 * 松手后 followBottom 被恢复，下一次内容增长就会瞬移回底部，表现为无动画的突然跳回。
 */
internal fun isScrolledToBottom(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    lastVisibleBottomPx: Int,
    safeBottomPx: Int,
    tolerancePx: Int
): Boolean {
    if (totalItemsCount <= 0 || lastVisibleIndex < totalItemsCount - 1) return false
    val gap = safeBottomPx - lastVisibleBottomPx
    return gap >= -tolerancePx && gap <= tolerancePx
}
