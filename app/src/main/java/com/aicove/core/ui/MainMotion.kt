package com.aicove.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin

/** 主界面局部面板只做绘制层动作，布局尺寸立即生效，避免交互完成后界面还在慢慢撑高。 */
fun smoothPanelEnter(
    expandFrom: Alignment.Vertical = Alignment.Top
): EnterTransition {
    val fromTop = expandFrom == Alignment.Top
    return fadeIn(
        tween(durationMillis = 70, easing = LinearOutSlowInEasing)
    ) + slideInVertically(
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 900f),
        initialOffsetY = { height -> if (fromTop) -height / 16 else height / 16 }
    ) + scaleIn(
        initialScale = 0.985f,
        transformOrigin = TransformOrigin(0.5f, if (fromTop) 0f else 1f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 1_000f)
    )
}

fun smoothPanelExit(
    shrinkTowards: Alignment.Vertical = Alignment.Top
): ExitTransition {
    val towardsTop = shrinkTowards == Alignment.Top
    return fadeOut(
        tween(durationMillis = 55, easing = FastOutLinearInEasing)
    ) + slideOutVertically(
        animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing),
        targetOffsetY = { height -> if (towardsTop) -height / 24 else height / 24 }
    ) + scaleOut(
        targetScale = 0.99f,
        transformOrigin = TransformOrigin(0.5f, if (towardsTop) 0f else 1f),
        animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing)
    )
}

fun smoothFadeEnter(): EnterTransition =
    fadeIn(tween(durationMillis = 80, easing = LinearOutSlowInEasing))

fun smoothFadeExit(): ExitTransition =
    fadeOut(tween(durationMillis = 55, easing = FastOutLinearInEasing))
