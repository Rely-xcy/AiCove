package com.aicove.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment

/** 主界面局部面板的统一运动曲线；整页导航仍使用 PageMotion。 */
fun smoothPanelEnter(
    expandFrom: Alignment.Vertical = Alignment.Top
): EnterTransition =
    fadeIn(
        tween(durationMillis = 180, easing = LinearOutSlowInEasing)
    ) + expandVertically(
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        expandFrom = expandFrom
    )

fun smoothPanelExit(
    shrinkTowards: Alignment.Vertical = Alignment.Top
): ExitTransition =
    fadeOut(
        tween(durationMillis = 130, easing = FastOutLinearInEasing)
    ) + shrinkVertically(
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        shrinkTowards = shrinkTowards
    )

fun smoothFadeEnter(): EnterTransition =
    fadeIn(tween(durationMillis = 180, easing = LinearOutSlowInEasing))

fun smoothFadeExit(): ExitTransition =
    fadeOut(tween(durationMillis = 130, easing = FastOutLinearInEasing))
