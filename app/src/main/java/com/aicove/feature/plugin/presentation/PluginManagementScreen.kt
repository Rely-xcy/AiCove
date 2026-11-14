package com.aicove.feature.plugin.presentation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicove.R
import com.aicove.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus

/** 插件安装包 MIME 类型白名单。 */
private val PluginPackageMimeTypes = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream"
)

/**
 * 插件管理的顶层页面。
 *
 * 它由应用壳层导航，而不是直接塞进 SettingsScreen：plugin 的 UI 需要复用 settings 的
 * 列表组件，反过来让 settings import plugin 会形成 feature/settings ↔ feature/plugin
 * 循环依赖。应用壳层同时依赖两者，没有这个问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: PluginsViewModel = hiltViewModel()
) {
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val shizukuGrantedIds by viewModel.shizukuGrantedPluginIds.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val dependencyStates by viewModel.dependencyStates.collectAsStateWithLifecycle()
    val devMode by viewModel.devMode.collectAsStateWithLifecycle()
    val devProjects by viewModel.devProjects.collectAsStateWithLifecycle()
    var selectedPluginId by remember { mutableStateOf<String?>(null) }
    val selectedPlugin = plugins.firstOrNull { it.id == selectedPluginId }
    val languageTag = currentLanguageTag()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    LaunchedEffect(selectedPlugin, selectedPluginId) {
        if (selectedPluginId != null && selectedPlugin == null) selectedPluginId = null
    }

    val navigateBack = {
        if (selectedPluginId != null) selectedPluginId = null else onNavigateBack()
    }
    BackHandler(onBack = navigateBack)

    Scaffold(
        containerColor = MaterialTheme.semanticColors.pageBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_plugins)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            imageVector = FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    if (selectedPluginId == null) {
                        IconButton(onClick = { picker.launch(PluginPackageMimeTypes) }) {
                            Icon(
                                imageVector = FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.plugins_import)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.semanticColors.pageBackground
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val plugin = selectedPlugin
            if (plugin == null) {
                PluginsSection(
                    plugins = plugins,
                    loaded = loaded,
                    onOpenDetail = { selectedPluginId = it.id },
                    onToggleEnabled = { item, enabled -> viewModel.setEnabled(item.id, enabled) },
                    onUninstall = { viewModel.uninstall(it.id) },
                    devMode = devMode,
                    devRootPath = viewModel.devRootPath,
                    devProjects = devProjects,
                    onSetDevMode = viewModel::setDevMode,
                    onRefreshDevProjects = viewModel::refreshDevProjects,
                    onInstallDevProject = viewModel::installDevProject
                )
            } else {
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val depState = dependencyStates[plugin.id]
                PluginDetailSection(
                    plugin = plugin,
                    onToggleEnabled = { viewModel.setEnabled(plugin.id, it) },
                    onUninstall = {
                        viewModel.uninstall(plugin.id)
                        selectedPluginId = null
                    },
                    bridgeFactory = { page, post, onClosePage ->
                        viewModel.createWebBridge(
                            page = page,
                            pluginVersion = page.pluginVersion,
                            languageTag = languageTag,
                            theme = { if (isDark) "dark" else "light" },
                            onClosePage = onClosePage,
                            post = post
                        )
                    },
                    onClosePage = { selectedPluginId = null },
                    dependencyState = depState,
                    onPrepareDependencies = { viewModel.prepareDependencies(plugin.id) },
                    shizukuGranted = plugin.content.shizuku != null && plugin.id in shizukuGrantedIds,
                    shizukuState = shizukuState,
                    onSetShizukuGranted = { viewModel.setShizukuGranted(plugin, it) }
                )
            }
        }
    }

    PluginImportHost(viewModel)
}
