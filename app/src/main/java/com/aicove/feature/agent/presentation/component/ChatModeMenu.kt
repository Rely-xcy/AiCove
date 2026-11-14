package com.aicove.feature.agent.presentation.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.aicove.R
import com.aicove.core.theme.Radius
import com.aicove.core.theme.Spacing
import com.aicove.core.theme.semanticColors
import com.aicove.core.ui.AppTextField
import com.aicove.core.ui.dialogTextFieldColors
import com.aicove.feature.agent.domain.model.AgentMode
import com.aicove.feature.agent.domain.model.GoalStatus
import com.aicove.feature.agent.presentation.SessionGoalUi

/**
 * 模式入口的配色：三档各自可辨，而不是统一淡底。
 * 旧实现按档取色，改弹窗时被换成 secondaryContainer——该色由推导得到
 * `lerp(secondary, surface, 0.86f)`，几乎等于背景，于是看起来「没有颜色」。
 */
@Composable
private fun modeContainerColor(mode: AgentMode): Color = when (mode) {
    AgentMode.PLAN -> MaterialTheme.colorScheme.primaryContainer
    AgentMode.AUTO -> MaterialTheme.colorScheme.error
    AgentMode.BUILD -> MaterialTheme.semanticColors.success
}

@Composable
private fun modeContentColor(mode: AgentMode): Color = when (mode) {
    AgentMode.PLAN -> MaterialTheme.colorScheme.onPrimaryContainer
    AgentMode.AUTO -> MaterialTheme.colorScheme.onError
    AgentMode.BUILD -> MaterialTheme.semanticColors.onSuccess
}

@Composable
internal fun ChatModeMenu(
    mode: AgentMode,
    goal: SessionGoalUi?,
    onSelectMode: (AgentMode) -> Unit,
    onSetGoal: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf(false) }
    var goalText by remember { mutableStateOf(TextFieldValue()) }
    val managed = goal?.status == GoalStatus.ACTIVE
    val accent = if (managed) MaterialTheme.colorScheme.primary else modeContainerColor(mode)
    val accentText = if (managed) MaterialTheme.colorScheme.onPrimary else modeContentColor(mode)
    Box {
        Surface(
            shape = RoundedCornerShape(Radius.pill),
            color = accent,
            onClick = { expanded = true }
        ) {
            Text(
                text = if (managed) stringResource(R.string.chat_mode_managed) else mode.name,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = accentText,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = Spacing.xs)
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.BottomStart,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                // 与顶栏 ⋯ 菜单同一套入场：缩放 + 透明度 + 位移，从按钮所在角展开。
                var isEntering by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { isEntering = false }
                val scale by animateFloatAsState(
                    targetValue = if (isEntering) 0.92f else 1f,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                    label = "mode-menu-scale"
                )
                val alpha by animateFloatAsState(
                    targetValue = if (isEntering) 0f else 1f,
                    animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing),
                    label = "mode-menu-alpha"
                )
                val offsetY by animateDpAsState(
                    targetValue = if (isEntering) 6.dp else 0.dp,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                    label = "mode-menu-offset-y"
                )
                Surface(
                    modifier = Modifier
                        .padding(bottom = 32.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                            translationY = offsetY.toPx()
                            transformOrigin = TransformOrigin(0f, 1f)
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.semanticColors.cardSurface,
                    border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder),
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.width(168.dp).padding(vertical = 4.dp)) {
                        AgentMode.entries.forEach { option ->
                            ModeMenuItem(
                                label = option.name,
                                active = !managed && mode == option,
                                dotColor = modeContainerColor(option)
                            ) {
                                expanded = false
                                onSelectMode(option)
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 14.dp, end = 12.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        ModeMenuItem(
                            label = stringResource(R.string.chat_mode_managed),
                            active = managed,
                            dotColor = MaterialTheme.colorScheme.primary
                        ) {
                            expanded = false
                            goalText = TextFieldValue(goal?.text.orEmpty())
                            editGoal = true
                        }
                    }
                }
            }
        }
    }
    if (editGoal) {
        AlertDialog(
            onDismissRequest = { editGoal = false },
            title = { Text(stringResource(R.string.chat_mode_managed)) },
            text = {
                AppTextField(
                    value = goalText,
                    onValueChange = { goalText = it },
                    placeholder = stringResource(R.string.chat_managed_goal_hint),
                    colors = dialogTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(enabled = goalText.text.isNotBlank(), onClick = {
                    onSetGoal(goalText.text.trim())
                    editGoal = false
                }) { Text(stringResource(R.string.chat_managed_start)) }
            },
            dismissButton = {
                TextButton(onClick = { editGoal = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 菜单项：左侧色点标出该档的入口色，右侧开启中打勾；按压有缩放与底色。 */
@Composable
private fun ModeMenuItem(
    label: String,
    active: Boolean,
    dotColor: Color,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = if (isPressed) {
            tween(durationMillis = 70, easing = FastOutLinearInEasing)
        } else {
            spring(dampingRatio = 0.8f, stiffness = 520f)
        },
        label = "mode-item-scale"
    )
    val pressedBackground by animateColorAsState(
        targetValue = if (isPressed) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 100, easing = LinearOutSlowInEasing),
        label = "mode-item-background"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(pressedBackground)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(dotColor, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (active) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}
