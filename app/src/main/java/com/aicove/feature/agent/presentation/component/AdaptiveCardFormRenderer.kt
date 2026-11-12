package com.aicove.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicove.R
import com.aicove.core.theme.Radius
import com.aicove.core.theme.Spacing
import com.aicove.core.theme.semanticColors
import com.aicove.core.ui.AppSwitch
import com.aicove.core.ui.AppTextField
import com.aicove.core.ui.adaptive.ActionSubmitElement
import com.aicove.core.ui.adaptive.AdaptiveCardAction
import com.aicove.core.ui.adaptive.AdaptiveCardParser
import com.aicove.core.ui.adaptive.CardElement
import com.aicove.core.ui.adaptive.CardPadding
import com.aicove.core.ui.adaptive.CodeBlockElement
import com.aicove.core.ui.adaptive.GridElement
import com.aicove.core.ui.adaptive.InputListElement
import com.aicove.core.ui.adaptive.InputSelectElement
import com.aicove.core.ui.adaptive.InputTextElement
import com.aicove.core.ui.adaptive.InputToggleElement
import com.aicove.core.ui.adaptive.MarkdownBlockElement
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2

@Composable
internal fun RenderGrid(
    element: GridElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hGap = (element.gapDp ?: 8).dp
    val vGap = (element.verticalGapDp ?: 8).dp
    val columns = maxOf(1, element.columns)
    val chunked = remember(element.items, columns) {
        element.items.chunked(columns)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .applyMargin(element.margin)
            .applyPadding(element.padding),
        verticalArrangement = Arrangement.spacedBy(vGap)
    ) {
        chunked.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hGap)
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        RenderElement(item, isCompact, onAction, onRefresh)
                    }
                }
                if (rowItems.size < columns) {
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 通用结构化表单与富组件原生渲染（支持插件与自定义面板）
// ════════════════════════════════════════════════════════════════

@Composable
internal fun RenderInputText(
    element: InputTextElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val currentVal = formState?.get(element.id)?.toString() ?: element.defaultValue
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (element.label.isNotBlank()) {
            Text(
                text = element.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        AppTextField(
            value = currentVal,
            onValueChange = { newVal -> formState?.set(element.id, newVal) },
            placeholder = element.placeholder.ifBlank { null },
            singleLine = !element.multiline,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun RenderInputSelect(
    element: InputSelectElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val currentVal = formState?.get(element.id)?.toString() ?: element.defaultValue
    var expanded by remember { mutableStateOf(false) }
    val currentChoice = element.choices.firstOrNull { it.value == currentVal } ?: element.choices.firstOrNull()
    val displayTitle = currentChoice?.title ?: currentVal

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = element.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = FeatherIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(MaterialTheme.semanticColors.cardSurface)
            ) {
                element.choices.forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = choice.title,
                                color = if (choice.value == currentVal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (choice.value == currentVal) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            formState?.set(element.id, choice.value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun RenderInputToggle(
    element: InputToggleElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val currentVal = (formState?.get(element.id) as? Boolean) ?: element.defaultValue
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = element.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            element.hint?.takeIf { it.isNotBlank() }?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AppSwitch(
            checked = currentVal,
            onCheckedChange = { formState?.set(element.id, it) }
        )
    }
}

@Composable
internal fun RenderInputList(
    element: InputListElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    @Suppress("UNCHECKED_CAST")
    val list = (formState?.get(element.id) as? List<String>) ?: element.defaultValues
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (element.label.isNotBlank()) {
            Text(
                text = element.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        list.forEachIndexed { index, param ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AppTextField(
                    value = param,
                    onValueChange = { newVal ->
                        val updated = list.toMutableList()
                        updated[index] = newVal
                        formState?.set(element.id, updated)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = element.placeholder.ifBlank {
                        stringResource(R.string.card_form_param_placeholder)
                    }
                )
                IconButton(
                    onClick = {
                        val updated = list.toMutableList()
                        updated.removeAt(index)
                        formState?.set(element.id, updated)
                    }
                ) {
                    Icon(
                        imageVector = FeatherIcons.Trash2,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .clickable {
                    val updated = list.toMutableList()
                    updated.add("")
                    formState?.set(element.id, updated)
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.card_form_add_param),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun RenderCodeBlock(
    element: CodeBlockElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val codeToShow = if (formState != null && element.code.contains("$")) {
        AdaptiveCardParser.interpolate(element.code, formState.toMap())
    } else {
        element.code
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.semanticColors.mutedSurface)
            .padding(Spacing.md)
    ) {
        Text(
            text = codeToShow,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun RenderMarkdownBlock(
    element: MarkdownBlockElement,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        MarkdownContent(
            text = element.content,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun RenderCard(
    element: CardElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(element.expandedDefault) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .applyMargin(element.margin),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .applyPadding(element.padding ?: CardPadding.all(12))
        ) {
            if (!element.title.isNullOrBlank()) {
                if (element.collapsible) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = element.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Text(
                        text = element.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            AnimatedVisibility(visible = !element.collapsible || expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    element.items.forEach { child ->
                        RenderElement(child, isCompact, onAction, onRefresh)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RenderActionSubmit(
    element: ActionSubmitElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val onSubmit = LocalCardOnSubmit.current
    Button(
        onClick = {
            onSubmit?.invoke(element.id, element.commandTemplate, formState?.toMap() ?: emptyMap())
        },
        shape = RoundedCornerShape(Radius.mdLarge),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = element.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
