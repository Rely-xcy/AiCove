package com.aicove.feature.agent.presentation.component

import com.aicove.feature.agent.presentation.AgentUIMessage
import com.aicove.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贴底校准的门槛：手指在屏幕上时绝不请求滚动。
 *
 * 回归点：手指落下到越过 touch slop 之间有一段窗口（慢滑时可长达几十帧），
 * 窗口里若还允许校准，逐帧的 `requestScrollToItem(最后一项, MAX)` 会把视口拽回底部，
 * 表现成「慢慢滑也会跳上去」。
 */
class ChatScrollFollowPolicyTest {

    @Test
    fun `异步重建列表期间保留已提交的历史锚点 key`() {
        val anchor = ChatRenderItem(
            message = AgentUIMessage(id = "history-42", role = MessageRole.USER, content = "history"),
            key = "history-42",
            contentType = "USER",
        )
        val updated = anchor.copy(message = anchor.message.copy(content = "updated"))
        var synchronousBuildCalled = false

        val duringBuild = resolveChatItemsKeepingPrevious(
            messagesReady = true,
            useAsyncItems = true,
            asyncItems = null,
            previousItems = listOf(anchor),
            buildSynchronously = {
                synchronousBuildCalled = true
                listOf(updated)
            },
        )

        assertEquals("后台构建未完成时 LazyColumn 的稳定 key 不能被清空", listOf("history-42"), duringBuild.map { it.key })
        assertFalse("长列表仍禁止退回主线程同步构建", synchronousBuildCalled)
        assertEquals(
            "构建完成后再提交新列表",
            listOf(updated),
            resolveChatItemsKeepingPrevious(true, true, listOf(updated), listOf(anchor)) { emptyList() },
        )
    }

    @Test
    fun `消息未就绪时不展示旧列表且短列表仍同步构建`() {
        val stale = ChatRenderItem(
            message = AgentUIMessage(id = "old-session", role = MessageRole.USER, content = "old"),
            key = "old-session",
            contentType = "USER",
        )
        var synchronousBuildCalled = false
        assertTrue(
            resolveChatItemsKeepingPrevious(false, true, null, listOf(stale)) {
                synchronousBuildCalled = true
                emptyList()
            }.isEmpty(),
        )
        assertFalse(synchronousBuildCalled)

        val fresh = stale.copy(message = stale.message.copy(id = "fresh-session"), key = "fresh-session")
        assertEquals(
            listOf(fresh),
            resolveChatItemsKeepingPrevious(true, false, null, listOf(stale)) {
                synchronousBuildCalled = true
                listOf(fresh)
            },
        )
        assertTrue("非异步列表必须即时采用最新构建结果", synchronousBuildCalled)
    }

    @Test
    fun `手指已落屏但还没越过 slop 时不校准`() {
        assertFalse(
            "手指在屏幕上就不允许请求滚动，否则会把正在滑动的视口拽回底部",
            shouldCalibrateToAnchor(
                followBottom = true,
                userScrollGesture = false,
                pointerDown = true,
                canScrollForward = true
            )
        )
    }

    @Test
    fun `流式贴底：跟随中、手指不在、还有可滚空间时要校准`() {
        assertTrue(
            shouldCalibrateToAnchor(
                followBottom = true,
                userScrollGesture = false,
                pointerDown = false,
                canScrollForward = true
            )
        )
    }

    @Test
    fun `只有底部停在安全区附近才算在底部`() {
        // 最后一项底部正好在安全区：在底部
        assertTrue(
            isScrolledToBottom(
                lastVisibleIndex = 9,
                totalItemsCount = 10,
                lastVisibleBottomPx = 1_000,
                safeBottomPx = 1_000,
                tolerancePx = 2
            )
        )
        // 在容差内：仍算在底部
        assertTrue(
            isScrolledToBottom(9, 10, 998, 1_000, 2)
        )
        // 最后一项还在屏幕上，但已经往上滑了 50px：不能算在底部
        assertFalse(
            "最后一项仍在视口内不等于在底部，否则松手后会恢复跟随并瞬移回底",
            isScrolledToBottom(9, 10, 950, 1_000, 2)
        )
        // 最后可见项不是最后一项：明确不在底部
        assertFalse(isScrolledToBottom(8, 10, 1_000, 1_000, 2))
    }

    @Test
    fun `滚动或浏览历史时冻结输入层安全区`() {
        assertFalse(shouldCommitInputBarReserve(false, false, false, false, false, 150, 150))
        assertFalse(shouldCommitInputBarReserve(true, true, false, false, false, 150, 150))
        assertFalse(shouldCommitInputBarReserve(true, false, true, false, false, 150, 150))
        assertFalse(shouldCommitInputBarReserve(true, false, false, true, false, 150, 150))
        assertFalse(shouldCommitInputBarReserve(true, false, false, false, true, 150, 150))
        assertTrue(shouldCommitInputBarReserve(true, false, false, false, false, 150, 150))
    }

    @Test
    fun `面板收起后停稳即回收旧留白而不要求先回底`() {
        assertFalse(shouldCommitInputBarReserve(false, true, true, false, true, 620, 150))
        assertFalse(shouldCommitInputBarReserve(false, false, false, true, true, 620, 150))
        assertTrue(shouldCommitInputBarReserve(false, false, false, false, false, 620, 150))
        assertFalse(shouldCommitInputBarReserve(false, false, false, false, false, 150, 620))
    }

    @Test
    fun `离开底部时收工的那一轮保持展开`() {
        assertTrue(
            "用户正在翻历史，此时把跑到一半的那一轮收起来会一次性删掉几十个 item",
            shouldHoldTurnExpanded(
                previousActiveTurnKey = "turn:u1",
                activeTurnKey = null,
                followBottom = false
            )
        )
        assertFalse(
            "人在底部收工则照常收起，校准循环会把视口带到新锚点",
            shouldHoldTurnExpanded("turn:u1", null, followBottom = true)
        )
        assertFalse(
            "已经收起的旧轮不能因为用户往上滑就重新展开——同样是结构突变",
            shouldHoldTurnExpanded(previousActiveTurnKey = null, activeTurnKey = null, followBottom = false)
        )
        assertFalse(
            "轮次仍在跑，本来就展开，不需要额外挂住",
            shouldHoldTurnExpanded("turn:u1", "turn:u1", followBottom = false)
        )
    }

    @Test
    fun `不跟随、手势中、已到底这三种情况都不校准`() {
        assertFalse(
            shouldCalibrateToAnchor(
                followBottom = false, userScrollGesture = false, pointerDown = false, canScrollForward = true
            )
        )
        assertFalse(
            shouldCalibrateToAnchor(
                followBottom = true, userScrollGesture = true, pointerDown = false, canScrollForward = true
            )
        )
        assertFalse(
            shouldCalibrateToAnchor(
                followBottom = true, userScrollGesture = false, pointerDown = false, canScrollForward = false
            )
        )
    }
}
