package com.aicove.feature.settings.presentation.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicove.R
import com.aicove.core.plugin.PluginSlotEntry
import com.aicove.core.theme.AppThemePreset
import com.aicove.core.theme.Spacing
import com.aicove.core.theme.isDynamicColorSupported
import com.aicove.core.theme.semanticColors
import com.aicove.feature.onboarding.domain.OnboardingStep
import com.aicove.feature.onboarding.presentation.onboardingTarget
import com.aicove.feature.settings.data.repository.AppThemeMode
import com.aicove.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicove.feature.terminal.data.repository.TerminalSettings
import compose.icons.FeatherIcons
import compose.icons.feathericons.BarChart2
import compose.icons.feathericons.Book
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.Box
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Database
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Image
import compose.icons.feathericons.Info
import compose.icons.feathericons.Lock
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Package
import compose.icons.feathericons.PieChart
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Save
import compose.icons.feathericons.Server
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Users
import compose.icons.feathericons.Zap

/** 设置首页：每个分区一个可点击的二级菜单入口。 */
@Composable
internal fun SettingsMenu(
    scrollState: ScrollState,
    themeMode: AppThemeMode,
    themePresetId: String?,
    dynamicColorEnabled: Boolean,
    terminalSettings: TerminalSettings,
    currentLanguageDisplayName: String,
    backgroundImagePath: String?,
    backgroundAlpha: Float,
    onOpenThemeSheet: () -> Unit,
    onOpenTerminalSettingsSheet: () -> Unit,
    onOpenBackgroundSheet: () -> Unit,
    onOpenLanguageSheet: () -> Unit,
    onOpenManual: () -> Unit,
    onRerunOnboarding: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpen: (SettingsSection) -> Unit,
    pluginEntries: List<PluginSlotEntry> = emptyList(),
    onOpenPluginEntry: (PluginSlotEntry) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── 通用设置 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_general))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Sliders,
                title = stringResource(SettingsSection.General.titleRes),
                onClick = { onOpen(SettingsSection.General) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Moon,
                title = stringResource(R.string.settings_theme_title),
                onClick = onOpenThemeSheet,
                trailing = {
                    val colorLabel = if (dynamicColorEnabled && isDynamicColorSupported) {
                        stringResource(R.string.theme_dynamic_color)
                    } else {
                        stringResource(AppThemePreset.findById(themePresetId).nameRes)
                    }
                    Text(
                        text = "${stringResource(themeMode.labelRes)} · $colorLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Terminal,
                title = stringResource(R.string.terminal_settings_title),
                onClick = onOpenTerminalSettingsSheet,
                trailing = {
                    Text(
                        text = stringResource(terminalSettings.theme.nameRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_background_image),
                onClick = onOpenBackgroundSheet,
                trailing = {
                    Text(
                        text = if (backgroundImagePath != null) "${BackgroundSettingsRepository.alphaToSlider(backgroundAlpha).toInt()}%"
                        else stringResource(R.string.settings_background_image_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(R.string.settings_language),
                onClick = onOpenLanguageSheet,
                trailing = {
                    Text(
                        text = currentLanguageDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            PluginSettingsRows(
                entries = pluginEntries,
                slot = "settings.general",
                onOpen = onOpenPluginEntry
            )
        }

        // ── AI 配置 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_ai))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Cloud,
                title = stringResource(SettingsSection.Providers.titleRes),
                onClick = { onOpen(SettingsSection.Providers) },
                modifier = Modifier.onboardingTarget(OnboardingStep.CONFIG_PROVIDER)
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Cpu,
                title = stringResource(SettingsSection.DefaultModels.titleRes),
                onClick = { onOpen(SettingsSection.DefaultModels) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Box,
                title = stringResource(SettingsSection.Mcp.titleRes),
                onClick = { onOpen(SettingsSection.Mcp) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Book,
                title = stringResource(SettingsSection.Skills.titleRes),
                onClick = { onOpen(SettingsSection.Skills) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Users,
                title = stringResource(SettingsSection.SubAgents.titleRes),
                onClick = { onOpen(SettingsSection.SubAgents) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Package,
                title = stringResource(R.string.settings_plugins),
                onClick = onOpenPlugins
            )
            SettingsDivider()
            SettingsRow(
                // 记忆用 Database：日志已经占了 FileText，两者不能同一图标（与 AI 工具里 memory 的图标也一致）。
                icon = FeatherIcons.Database,
                title = stringResource(SettingsSection.Memory.titleRes),
                onClick = { onOpen(SettingsSection.Memory) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(SettingsSection.Prompts.titleRes),
                onClick = { onOpen(SettingsSection.Prompts) }
            )
            PluginSettingsRows(
                entries = pluginEntries,
                slot = "settings.ai",
                onOpen = onOpenPluginEntry
            )
        }

        // ── 运行环境 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_environment))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.HardDrive,
                title = stringResource(SettingsSection.Container.titleRes),
                onClick = { onOpen(SettingsSection.Container) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(SettingsSection.Proxy.titleRes),
                onClick = { onOpen(SettingsSection.Proxy) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Server,
                title = stringResource(SettingsSection.RemoteServers.titleRes),
                onClick = { onOpen(SettingsSection.RemoteServers) }
            )
            PluginSettingsRows(
                entries = pluginEntries,
                slot = "settings.environment",
                onOpen = onOpenPluginEntry
            )
        }

        // ── 权限与后台 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_permissions))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Lock,
                title = stringResource(SettingsSection.Permissions.titleRes),
                onClick = { onOpen(SettingsSection.Permissions) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(SettingsSection.AppPermissions.titleRes),
                onClick = { onOpen(SettingsSection.AppPermissions) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(SettingsSection.BackgroundRun.titleRes),
                onClick = { onOpen(SettingsSection.BackgroundRun) }
            )
            PluginSettingsRows(
                entries = pluginEntries,
                slot = "settings.permissions",
                onOpen = onOpenPluginEntry
            )
        }

        // ── 数据与诊断 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_data))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.BarChart2,
                title = stringResource(SettingsSection.TokenStats.titleRes),
                onClick = { onOpen(SettingsSection.TokenStats) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.PieChart,
                title = stringResource(SettingsSection.Storage.titleRes),
                onClick = { onOpen(SettingsSection.Storage) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Save,
                title = stringResource(SettingsSection.Backup.titleRes),
                onClick = { onOpen(SettingsSection.Backup) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.FileText,
                title = stringResource(SettingsSection.Log.titleRes),
                onClick = { onOpen(SettingsSection.Log) }
            )
            PluginSettingsRows(
                entries = pluginEntries,
                slot = "settings.data",
                onOpen = onOpenPluginEntry
            )
        }

        // ── 帮助与关于 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_help))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.BookOpen,
                title = stringResource(R.string.settings_user_guide),
                onClick = onOpenManual
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.settings_rerun_onboarding),
                onClick = onRerunOnboarding
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Info,
                title = stringResource(SettingsSection.About.titleRes),
                onClick = { onOpen(SettingsSection.About) }
            )
            PluginSettingsRows(
                entries = pluginEntries,
                slot = "settings.help",
                onOpen = onOpenPluginEntry
            )
        }
    }
}
