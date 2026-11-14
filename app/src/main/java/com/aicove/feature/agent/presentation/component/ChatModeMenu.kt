package com.aicove.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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
    Box {
        Surface(
            shape = RoundedCornerShape(Radius.pill),
            color = MaterialTheme.colorScheme.secondaryContainer,
            onClick = { expanded = true }
        ) {
            Text(
                text = if (managed) stringResource(R.string.chat_mode_managed) else mode.name,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = Spacing.xs)
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.BottomStart,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    modifier = Modifier.width(168.dp).padding(bottom = 32.dp),
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.semanticColors.cardSurface,
                    border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder)
                ) {
                    Column(Modifier.padding(Spacing.xs)) {
                        AgentMode.entries.forEach { option ->
                            Text(
                                text = option.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (!managed && mode == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm))
                                    .clickable { expanded = false; onSelectMode(option) }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.md)
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.semanticColors.subtleBorder)
                        Text(
                            text = stringResource(R.string.chat_mode_managed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (managed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm))
                                .clickable {
                                    expanded = false
                                    goalText = TextFieldValue(goal?.text.orEmpty())
                                    editGoal = true
                                }.padding(horizontal = Spacing.md, vertical = Spacing.md)
                        )
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
