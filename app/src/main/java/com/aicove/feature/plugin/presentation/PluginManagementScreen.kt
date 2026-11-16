package com.aicove.feature.plugin.presentation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicove.R
import com.aicove.core.theme.Spacing
import com.aicove.core.theme.semanticColors
import com.aicove.core.ui.AdaptiveModalBottomSheet
import com.aicove.core.ui.AppSwitch
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2

private val PluginPackageMimeTypes = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream"
)

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
    val busyPluginIds by viewModel.busyPluginIds.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val devRefreshing by viewModel.devRefreshing.collectAsStateWithLifecycle()
    var selectedPluginId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDevDir by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedStoreItem by remember { mutableStateOf<PluginStoreItem?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var confirmUninstall by rememberSaveable { mutableStateOf(false) }
    val pageStateHolder = rememberSaveableStateHolder()
    val selectedPlugin = plugins.firstOrNull { it.id == selectedPluginId }
    val selectedDevProject = devProjects.firstOrNull { it.dirName == selectedDevDir }
    val languageTag = currentLanguageTag()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.operationMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    LaunchedEffect(loaded, selectedPlugin, selectedPluginId) {
        if (loaded && selectedPluginId != null && selectedPlugin == null) selectedPluginId = null
    }
    LaunchedEffect(devMode, devProjects, selectedDevDir) {
        if (!devMode || selectedDevProject == null) selectedDevDir = null
    }

    val isDetail = selectedPluginId != null || selectedDevDir != null || selectedStoreItem != null
    val navigateBack = {
        when {
            selectedPluginId != null -> selectedPluginId = null
            selectedDevDir != null -> selectedDevDir = null
            selectedStoreItem != null -> selectedStoreItem = null
            else -> onNavigateBack()
        }
    }
    BackHandler(onBack = navigateBack)

    Scaffold(
        containerColor = MaterialTheme.semanticColors.pageBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        selectedPlugin?.displayName(languageTag)
                            ?: selectedDevProject?.displayName(languageTag)
                            ?: selectedStoreItem?.plugin?.name?.resolve(languageTag)
                            ?: stringResource(R.string.settings_plugins),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (!isDetail) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(FeatherIcons.Settings, contentDescription = stringResource(R.string.plugin_layout_settings))
                        }
                        IconButton(
                            onClick = { picker.launch(PluginPackageMimeTypes) },
                            enabled = loaded && (importState is PluginImportState.Idle || importState is PluginImportState.Failed)
                        ) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.plugins_import))
                        }
                    } else if (selectedPlugin != null) {
                        IconButton(
                            onClick = { confirmUninstall = true },
                            enabled = selectedPlugin.id !in busyPluginIds
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.plugins_uninstall),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.semanticColors.pageBackground)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val plugin = selectedPlugin
            val devProject = selectedDevProject
            val storeItem = selectedStoreItem
            when {
                plugin != null -> {
                    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val depState = dependencyStates[plugin.id]?.takeIf {
                        it.installRevision == plugin.record.installRevision
                    }
                    PluginDetailSection(
                        plugin = plugin,
                        onToggleEnabled = { viewModel.setEnabled(plugin.id, it) },
                        operationRunning = plugin.id in busyPluginIds,
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
                devProject != null -> PluginDevDetailSection(
                    project = devProject,
                    plugins = plugins,
                    devRootPath = viewModel.devRootPath,
                    onInstall = { viewModel.installDevProject(devProject) },
                    operationRunning = devProject.pluginId in busyPluginIds
                )
                storeItem != null -> PluginStoreDetailSection(
                    item = storeItem,
                    onBack = { selectedStoreItem = null }
                )
                else -> pageStateHolder.SaveableStateProvider("plugin-tabs") {
                    PluginsSection(
                        plugins = plugins,
                        loaded = loaded,
                        busyPluginIds = busyPluginIds,
                        onOpenDetail = { selectedPluginId = it.id },
                        onToggleEnabled = { item, enabled -> viewModel.setEnabled(item.id, enabled) },
                        onUninstall = { viewModel.uninstall(it.id) },
                        devMode = devMode,
                        devRootPath = viewModel.devRootPath,
                        devProjects = devProjects,
                        refreshing = refreshing,
                        devRefreshing = devRefreshing,
                        onRefresh = viewModel::refresh,
                        onRefreshDevProjects = viewModel::refreshDevProjects,
                        onOpenDevDetail = { selectedDevDir = it.dirName },
                        onSetDevEnabled = viewModel::setDevProjectEnabled,
                        onOpenStoreDetail = { selectedStoreItem = it }
                    )
                }
            }
        }
    }

    if (showSettings) {
        AdaptiveModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Text(stringResource(R.string.plugin_layout_settings), style = MaterialTheme.typography.titleLarge)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(end = Spacing.md)) {
                        Text(stringResource(R.string.plugins_dev_mode), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.plugins_dev_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AppSwitch(checked = devMode, onCheckedChange = viewModel::setDevMode)
                }
                Text(
                    stringResource(R.string.plugin_layout_developer_path, viewModel.devRootPath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmUninstall && selectedPlugin != null) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.plugins_uninstall_confirm_title)) },
            text = { Text(stringResource(R.string.plugins_uninstall_confirm_message, selectedPlugin.displayName(languageTag))) },
            confirmButton = {
                TextButton(
                    enabled = selectedPlugin.id !in busyPluginIds,
                    onClick = {
                        confirmUninstall = false
                        viewModel.uninstall(selectedPlugin.id)
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
    PluginImportHost(viewModel)
}
