package com.aicove

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RightDrawerGesturePolicyTest {

    @Test
    fun start_requiresRightInnerBandAndExcludesSystemEdge() {
        val width = 1_000f
        val activationWidth = 280f
        val edgeExclusion = 50f

        assertFalse(isRightDrawerGestureStart(500f, width, activationWidth, edgeExclusion))
        assertTrue(isRightDrawerGestureStart(800f, width, activationWidth, edgeExclusion))
        assertFalse(isRightDrawerGestureStart(980f, width, activationWidth, edgeExclusion))
    }

    @Test
    fun open_requiresDeliberateMostlyHorizontalLeftSwipe() {
        assertFalse(shouldOpenRightDrawerGesture(horizontal = -70f, vertical = 5f, threshold = 88f))
        assertFalse(shouldOpenRightDrawerGesture(horizontal = -100f, vertical = 60f, threshold = 88f))
        assertFalse(shouldOpenRightDrawerGesture(horizontal = 100f, vertical = 5f, threshold = 88f))
        assertTrue(shouldOpenRightDrawerGesture(horizontal = -100f, vertical = 30f, threshold = 88f))
    }
}
