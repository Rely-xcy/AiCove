package com.aicove.feature.plugin.presentation

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicove.R
import com.aicove.core.theme.Radius
import com.aicove.core.theme.Spacing
import com.aicove.core.theme.semanticColors
import com.aicove.core.util.FileLogger
import com.aicove.core.ui.AppSwitch
import com.aicove.core.ui.FloatingTabBar
import com.aicove.core.ui.FloatingTabItem
import com.aicove.core.ui.SegmentedTabs
import com.aicove.core.ui.SwipeToDeleteRow
import com.aicove.feature.agent.domain.shizuku.ShizukuState
import com.aicove.feature.plugin.domain.InstalledPlugin
import com.aicove.feature.plugin.domain.PluginHookModes
import com.aicove.feature.plugin.domain.PluginIcon
import com.aicove.feature.plugin.domain.PluginMcpServer
import com.aicove.feature.plugin.domain.PluginSettingsPage
import com.aicove.feature.plugin.domain.PluginStatus
import com.aicove.feature.settings.presentation.component.LazyGroupItem
import com.aicove.feature.settings.presentation.component.McpPill
import com.aicove.feature.settings.presentation.component.ModelSearchField
import com.aicove.feature.settings.presentation.component.SettingsDivider
import com.aicove.feature.settings.presentation.component.SettingsEmptyState
import com.aicove.feature.settings.presentation.component.SettingsGroup
import com.aicove.feature.settings.presentation.component.SettingsGroupHeader
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Code
import compose.icons.feathericons.ShoppingBag
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Package
import compose.icons.feathericons.Search
import kotlinx.coroutines.delay

/** 当前界面语言标签，用于挑选插件自带的多语言文本。 */
@Composable
internal fun currentLanguageTag(): String = LocalConfiguration.current.locales[0].toLanguageTag()

/** 展示用名称：清单可读时取本地化名称，否则（文件损坏等）退回插件 ID。 */
internal fun InstalledPlugin.displayName(languageTag: String): String =
    manifest?.name?.resolve(languageTag) ?: id

@Composable
internal fun mcpDescription(server: PluginMcpServer): String {
    val transport = stringResource(
        if (server.config.isStdio) R.string.plugins_mcp_transport_stdio else R.string.plugins_mcp_transport_http
    )
    val target = if (server.config.isStdio) {
        (listOfNotNull(server.config.command) + server.config.args).joinToString(" ")
    } else {
        server.config.url.orEmpty()
    }
    return if (target.isBlank()) transport else "$transport · $target"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginsSection(
    plugins: List<InstalledPlugin>,
    loaded: Boolean,
    onOpenDetail: (InstalledPlugin) -> Unit,
    onToggleEnabled: (InstalledPlugin, Boolean) -> Unit,
    onUninstall: (InstalledPlugin) -> Unit = {},
    devMode: Boolean = false,
    devRootPath: String = "",
    devProjects: List<PluginDevProject> = emptyList(),
    onRefreshDevProjects: () -> Unit,
    onOpenDevDetail: (PluginDevProject) -> Unit,
    onSetDevEnabled: (PluginDevProject, Boolean) -> Unit,
    onOpenStoreDetail: (PluginStoreItem) -> Unit,
    refreshing: Boolean,
    devRefreshing: Boolean,
    onRefresh: () -> Unit,
    busyPluginIds: Set<String> = emptySet(),
    storeViewModel: PluginStoreViewModel = hiltViewModel()
) {
    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.plugins_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val languageTag = currentLanguageTag()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var pluginToUninstall by remember { mutableStateOf<InstalledPlugin?>(null) }
    val storeState by storeViewModel.state.collectAsStateWithLifecycle()
    val pageCount = if (devMode) 3 else 2
    val installedListState = rememberLazyListState()
    val devListState = rememberLazyListState()

    val filteredPlugins = remember(plugins, searchQuery, languageTag) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            plugins
        } else {
            plugins.filter { plugin ->
                plugin.displayName(languageTag).lowercase().contains(query) ||
                    plugin.id.lowercase().contains(query) ||
                    plugin.manifest?.publisher?.lowercase()?.contains(query) == true ||
                    plugin.manifest?.description?.resolve(languageTag)?.lowercase()?.contains(query) == true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 模式切换改变页数，底栏也必须重建以清除缓存的页签位置。
        key(pageCount) {
            val pagerState = rememberPagerState { pageCount }
            val listScrolling by remember {
                derivedStateOf {
                    when {
                        pagerState.currentPage == 0 -> installedListState.isScrollInProgress
                        devMode && pagerState.currentPage == 1 -> devListState.isScrollInProgress
                        else -> false
                    }
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when {
                    page == 0 -> PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            onRefresh()
                            storeViewModel.refresh()
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            state = installedListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 112.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            if (plugins.isEmpty()) {
                                item(key = "empty", contentType = "empty") {
                                    SettingsEmptyState(
                                        icon = FeatherIcons.Package,
                                        title = stringResource(R.string.plugins_empty_title),
                                        hint = stringResource(R.string.plugins_empty_hint),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                                    )
                                }
                            }
                            if (plugins.size >= 3) {
                                item(key = "search", contentType = "search") {
                                    ModelSearchField(
                                        query = searchQuery,
                                        onQueryChange = { searchQuery = it },
                                        placeholder = stringResource(R.string.plugins_search_placeholder),
                                        modifier = Modifier.padding(bottom = Spacing.xs)
                                    )
                                }
                            }

                            if (filteredPlugins.isEmpty() && plugins.isNotEmpty()) {
                                item(key = "search-empty", contentType = "empty") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 48.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                                        ) {
                                            Icon(
                                                FeatherIcons.Search,
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                            Text(
                                                text = stringResource(R.string.plugins_search_empty),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    items = filteredPlugins,
                                    key = { _, plugin -> plugin.id },
                                    contentType = { _, _ -> "plugin-row" }
                                ) { index, plugin ->
                                    LazyGroupItem(index = index, lastIndex = filteredPlugins.lastIndex) {
                                        PluginRow(
                                            plugin = plugin,
                                            languageTag = languageTag,
                                            update = storeState.updatesByPluginId[plugin.id],
                                            onToggleEnabled = { onToggleEnabled(plugin, it) },
                                            contentShape = pluginRowShape(index, filteredPlugins.lastIndex),
                                            onClick = { onOpenDetail(plugin) },
                                            onDelete = { pluginToUninstall = plugin },
                                            operationRunning = plugin.id in busyPluginIds
                                        )
                                    }
                                }
                            }
                        }
                    }
                    devMode && page == 1 -> DevPluginsPage(
                        projects = devProjects,
                        plugins = plugins,
                        devRootPath = devRootPath,
                        listState = devListState,
                        refreshing = devRefreshing,
                        onRefresh = onRefreshDevProjects,
                        onOpenDetail = onOpenDevDetail,
                        onSetEnabled = onSetDevEnabled,
                        busyPluginIds = busyPluginIds
                    )
                    else -> PluginStoreSection(
                        onOpenDetail = onOpenStoreDetail,
                        viewModel = storeViewModel
                    )
                }
            }
            FloatingTabBar(
                pagerState = pagerState,
                items = buildList {
                    add(FloatingTabItem(FeatherIcons.Package, stringResource(R.string.plugins_tab_installed)))
                    if (devMode) add(FloatingTabItem(FeatherIcons.Code, stringResource(R.string.plugins_tab_dev)))
                    add(FloatingTabItem(FeatherIcons.ShoppingBag, stringResource(R.string.plugins_tab_store)))
                },
                maskColor = MaterialTheme.semanticColors.pageBackground,
                isScrolling = listScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    pluginToUninstall?.let { target ->
        AlertDialog(
            onDismissRequest = { pluginToUninstall = null },
            title = { Text(stringResource(R.string.plugins_uninstall_confirm_title)) },
            text = {
                Text(stringResource(R.string.plugins_uninstall_confirm_message, target.displayName(languageTag)))
            },
            confirmButton = {
                TextButton(onClick = {
                    pluginToUninstall = null
                    onUninstall(target)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pluginToUninstall = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * 插件行与 [LazyGroupItem] 卡片轮廓一致的裁剪形状：首项圆上角、末项圆下角，中间项直角。
 * 只用于表层点击层的裁剪，不参与背景与描边。
 */
private fun pluginRowShape(index: Int, lastIndex: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) Radius.lg else 0.dp,
    topEnd = if (index == 0) Radius.lg else 0.dp,
    bottomStart = if (index == lastIndex) Radius.lg else 0.dp,
    bottomEnd = if (index == lastIndex) Radius.lg else 0.dp
)

@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    languageTag: String,
    update: PluginStoreItem?,
    onToggleEnabled: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    contentShape: Shape = RectangleShape,
    operationRunning: Boolean = false
) {
    val version = plugin.manifest?.version?.toString() ?: plugin.record.version
    val publisher = plugin.manifest?.publisher
    val switchEnabled = plugin.status == PluginStatus.OK && !operationRunning

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        deleteEnabled = !operationRunning,
        contentShape = contentShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(Radius.sm)
                    )
            ) {
                PluginIconContent(
                    icon = plugin.content.icon,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 22.dp,
                    shape = RoundedCornerShape(Radius.sm)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.displayName(languageTag),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (update != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                R.string.plugin_layout_update_version,
                                plugin.record.version,
                                update.plugin.version.orEmpty()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    McpPill(
                        text = "v$version",
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        backgroundColor = MaterialTheme.colorScheme.secondaryContainer
                    )

                    if (!publisher.isNullOrBlank()) {
                        McpPill(
                            text = publisher,
                            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            backgroundColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    }

                    if (update != null) {
                        McpPill(
                            text = stringResource(R.string.plugin_layout_update_available),
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            if (operationRunning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                AppSwitch(
                    checked = plugin.record.enabled && plugin.status == PluginStatus.OK,
                    onCheckedChange = onToggleEnabled,
                    enabled = switchEnabled
                )
            }
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 插件详情与配置页面：
 * 采用 SegmentedTabs 分段控件，将【插件管理（AiCove 固定配置）】与【插件配置（WebView 专属配置页面）】清晰分离，
 * 若插件未声明专属配置页面（configPage）则直接全屏展示原生管理页面，无缝融入 App 主题。
 */
@Composable
internal fun PluginDetailSection(
    plugin: InstalledPlugin,
    onToggleEnabled: (Boolean) -> Unit,
    bridgeFactory: ((page: PluginSettingsPage, post: (String) -> Unit, onClosePage: () -> Unit) -> PluginWebBridge)? = null,
    onClosePage: () -> Unit = {},
    dependencyState: PluginDependencyUiState? = null,
    onPrepareDependencies: () -> Unit = {},
    shizukuGranted: Boolean = false,
    shizukuState: ShizukuState = ShizukuState.NOT_RUNNING,
    onSetShizukuGranted: (Boolean) -> Unit = {},
    operationRunning: Boolean = false
) {
    val languageTag = currentLanguageTag()
    val configPage = plugin.content.configPage
    val hasConfigPage = configPage != null && bridgeFactory != null
    var selectedTab by remember(plugin.id) { mutableIntStateOf(0) }

    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.semanticColors
    val fontScale = LocalConfiguration.current.fontScale
    val themeDark = PluginThemeTokens.isDark(colorScheme)
    val themeVariables = remember(colorScheme, semantic, fontScale) {
        PluginThemeTokens.variables(colorScheme, semantic, fontScale)
    }
    val pageBackground = semantic.pageBackground.toArgb()

    // 详情页打开后预热专属配置页：切到「插件配置」时直接复用已加载页面，避免首次进入现建 WebView 的白屏。
    // WebView 尚未挂到窗口，先按屏幕尺寸布局再加载，否则页面会按 0×0 视口完成首次布局。
    LaunchedEffect(plugin.id, hasConfigPage) {
        if (!hasConfigPage) return@LaunchedEffect
        val url = PluginWebViewFactory.pageUrl(configPage)
        delay(200)
        if (PluginWebViewCache.isLoaded(configPage.cacheKey, url)) return@LaunchedEffect
        runCatching {
            val webView = PluginWebViewCache.take(configPage.cacheKey) ?: PluginWebViewFactory.create(
                context = context,
                page = configPage,
                dark = themeDark,
                backgroundColor = pageBackground,
                themeVariables = { themeVariables },
                onPageFinished = { view ->
                    PluginWebViewCache.markLoaded(configPage.cacheKey, url)
                    view.evaluateJavascript(PluginThemeTokens.applyScript(themeVariables), null)
                },
                onMainFrameError = { PluginWebViewCache.clearLoading(configPage.cacheKey) }
            )
            PluginWebViewCache.put(configPage.cacheKey, webView)
            if (PluginWebViewCache.takeBridge(configPage.cacheKey) == null) {
                val bridge = bridgeFactory(
                    configPage,
                    { script -> runCatching { webView.evaluateJavascript(script, null) } },
                    onClosePage
                )
                webView.addJavascriptInterface(bridge, "__aicodeBridge")
                PluginWebViewCache.putBridge(configPage.cacheKey, bridge)
            }
            PluginWebViewCache.markLoading(configPage.cacheKey, url)
            val metrics = context.resources.displayMetrics
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            webView.loadUrl(url)
        }.onFailure { error ->
            FileLogger.w("PluginsSection", "插件配置页预热失败", error)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (hasConfigPage) {
            SegmentedTabs(
                selected = selectedTab,
                labels = listOf(
                    stringResource(R.string.plugins_tab_manage),
                    stringResource(R.string.plugins_tab_config)
                ),
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.sm, bottom = Spacing.sm)
            )
        }

        if (!hasConfigPage || selectedTab == 0) {
            // Tab 0: AiCove 固定配置与管理页面
            PluginNativeConfigContent(
                plugin = plugin,
                languageTag = languageTag,
                hasConfigPage = hasConfigPage,
                onNavigateToConfigTab = { selectedTab = 1 },
                onToggleEnabled = onToggleEnabled,
                operationRunning = operationRunning,
                dependencyState = dependencyState,
                onPrepareDependencies = onPrepareDependencies,
                shizukuGranted = shizukuGranted,
                shizukuState = shizukuState,
                onSetShizukuGranted = onSetShizukuGranted
            )
        } else {
            // Tab 1: 插件自己的专属 WebView 配置页面
            PluginWebPanelScreen(
                page = configPage,
                onClosePage = onClosePage,
                bridgeFactory = { post, onClose ->
                    bridgeFactory(configPage, post, onClose)
                },
                modifier = Modifier.fillMaxSize(),
                applySystemBarInsets = false
            )
        }
    }
}

/** 插件原生的固定配置列表。 */
@Composable
private fun PluginNativeConfigContent(
    plugin: InstalledPlugin,
    languageTag: String,
    hasConfigPage: Boolean,
    onNavigateToConfigTab: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    dependencyState: PluginDependencyUiState? = null,
    onPrepareDependencies: () -> Unit = {},
    shizukuGranted: Boolean = false,
    shizukuState: ShizukuState = ShizukuState.NOT_RUNNING,
    onSetShizukuGranted: (Boolean) -> Unit = {},
    operationRunning: Boolean = false
) {
    val manifest = plugin.manifest

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Spacer(Modifier.height(Spacing.xs))

        PluginHeaderCard(
            name = plugin.displayName(languageTag),
            description = manifest?.description?.resolve(languageTag).orEmpty(),
            icon = plugin.content.icon
        )

        // 启停开关
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.plugins_enable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = enableHint(plugin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (operationRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    AppSwitch(
                        checked = plugin.record.enabled && plugin.status == PluginStatus.OK,
                        onCheckedChange = onToggleEnabled,
                        enabled = plugin.status == PluginStatus.OK
                    )
                }
            }
        }

        plugin.content.shizuku?.let { declaration ->
            SettingsGroupHeader(text = stringResource(R.string.plugin_layout_permissions))
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.plugins_shizuku_scope),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = declaration.purpose,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                if (shizukuGranted) R.string.plugins_shizuku_authorized_hint
                                else R.string.plugins_shizuku_unauthorized_hint
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (shizukuGranted) MaterialTheme.semanticColors.success
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (shizukuState != ShizukuState.READY) {
                            Text(
                                text = stringResource(
                                    R.string.plugins_shizuku_system_state,
                                    stringResource(shizukuState.statusLabelRes())
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    AppSwitch(
                        checked = shizukuGranted,
                        onCheckedChange = onSetShizukuGranted,
                        enabled = plugin.isActive && !operationRunning
                    )
                }
            }
        }

        val dependency = plugin.content.runtime?.dependencies
        if (dependency != null) {
            SettingsGroupHeader(text = stringResource(R.string.plugins_section_dependencies))
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val ready = dependencyState?.ready ?: plugin.record.dependenciesReady
                        Text(
                            text = stringResource(
                                if (ready) R.string.plugins_dependencies_ready
                                else R.string.plugins_dependencies_missing
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = dependency.file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        dependencyState?.message?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = onPrepareDependencies,
                        enabled = dependencyState?.running != true && !operationRunning
                    ) {
                        Text(
                            stringResource(
                                if (dependencyState?.running == true) R.string.plugins_dependencies_preparing
                                else R.string.plugins_dependencies_prepare
                            )
                        )
                    }
                }
            }
        }

        if (hasConfigPage) {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToConfigTab() }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.plugins_config_card_title),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.plugins_config_card_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = FeatherIcons.ExternalLink,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (manifest != null) {
            SettingsGroupHeader(text = stringResource(R.string.plugins_section_info))
            SettingsGroup {
                InfoRow(stringResource(R.string.plugins_info_version), manifest.version.toString())
                SettingsDivider()
                InfoRow(stringResource(R.string.plugins_info_publisher), manifest.publisher)
                SettingsDivider()
                InfoRow(stringResource(R.string.plugins_info_license), manifest.license)
                SettingsDivider()
                InfoRow(stringResource(R.string.plugins_info_repository), manifest.repository)
                SettingsDivider()
                InfoRow(stringResource(R.string.plugins_info_origin), stringResource(R.string.plugins_origin_local))
            }

            SettingsGroupHeader(text = stringResource(R.string.plugins_section_contents))
            SettingsGroup {
                val skills = plugin.content.skills
                val agents = plugin.content.agents
                val workspaceTools = plugin.content.workspaceTools
                val uiEntries = plugin.content.uiEntries
                val aiTools = plugin.content.tools
                val hooks = plugin.content.hooks
                val mcpServers = plugin.content.mcp
                val providers = plugin.content.providers
                val shizuku = plugin.content.shizuku
                if (skills.isEmpty() && agents.isEmpty() && workspaceTools.isEmpty() && uiEntries.isEmpty() &&
                    aiTools.isEmpty() && hooks.isEmpty() && mcpServers.isEmpty() && providers.isEmpty() && shizuku == null
                ) {
                    Text(
                        text = stringResource(R.string.plugins_no_contents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 12.dp)
                    )
                }
                val rows = skills.map {
                    stringResource(R.string.plugins_content_skill, it.skill.name) to it.skill.description
                } + agents.map {
                    stringResource(R.string.plugins_content_agent, it.definition.name) to it.definition.description
                } + workspaceTools.map {
                    stringResource(R.string.plugins_content_tool, it.title.resolve(languageTag)) to
                        it.description.resolve(languageTag)
                } + uiEntries.map {
                    stringResource(R.string.plugins_content_settings_page, it.title.resolve(languageTag)) to it.slot
                } + aiTools.map {
                    stringResource(R.string.plugins_content_ai_tool, it.title.resolve(languageTag)) to
                        it.description.resolve(languageTag)
                } + hooks.map {
                    val title = when (it.mode) {
                        PluginHookModes.TRANSFORM -> stringResource(R.string.plugins_content_transform_hook, it.event)
                        PluginHookModes.REVIEW -> stringResource(R.string.plugins_content_review_hook, it.event)
                        else -> stringResource(R.string.plugins_content_hook, it.event)
                    }
                    title to it.handler
                } + mcpServers.map {
                    stringResource(R.string.plugins_content_mcp, it.originalName) to mcpDescription(it)
                } + providers.map {
                    stringResource(R.string.plugins_content_provider, it.name.resolve(languageTag)) to it.baseUrl
                } + listOfNotNull(
                    shizuku?.let {
                        stringResource(R.string.plugins_content_shizuku, it.purpose) to it.purpose
                    }
                )
                rows.forEachIndexed { index, (title, description) ->
                    if (index > 0) SettingsDivider()
                    ContentRow(title = title, description = description, active = plugin.isActive)
                }
            }
        }

    }
}

@Composable
private fun enableHint(plugin: InstalledPlugin): String = when {
    plugin.status == PluginStatus.INVALID -> stringResource(R.string.plugins_hint_incompatible)
    plugin.status != PluginStatus.OK -> stringResource(R.string.plugins_hint_damaged)
    else -> stringResource(R.string.plugins_enable_hint)
}

@Composable
private fun PluginHeaderCard(name: String, description: String, icon: PluginIcon?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.mdLarge)),
                contentAlignment = Alignment.Center
            ) {
                PluginIconContent(
                    icon = icon,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 26.dp,
                    shape = RoundedCornerShape(Radius.mdLarge)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.45f)
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.55f)
        )
    }
}

@Composable
private fun ContentRow(title: String, description: String, active: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (active) 1f else 0.5f)
            .padding(horizontal = Spacing.lg, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun ShizukuState.statusLabelRes(): Int = when (this) {
    ShizukuState.NOT_RUNNING -> R.string.settings_shizuku_status_not_running
    ShizukuState.PERMISSION_DENIED -> R.string.settings_shizuku_status_denied
    ShizukuState.READY -> R.string.settings_shizuku_status_ready
}
