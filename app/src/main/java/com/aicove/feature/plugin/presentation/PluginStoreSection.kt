package com.aicove.feature.plugin.presentation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicove.R
import com.aicove.core.theme.Spacing
import com.aicove.core.theme.semanticColors
import com.aicove.feature.plugin.data.index.PluginIndexDefaults
import com.aicove.feature.settings.presentation.component.LazyGroupItem
import com.aicove.feature.settings.presentation.component.McpPill
import com.aicove.feature.settings.presentation.component.ModelSearchField
import com.aicove.feature.settings.presentation.component.SettingsDivider
import com.aicove.feature.settings.presentation.component.SettingsEmptyState
import com.aicove.feature.settings.presentation.component.SettingsGroup
import com.aicove.feature.settings.presentation.component.SettingsGroupHeader
import com.aicove.feature.settings.presentation.component.SettingsRow
import com.aicove.feature.settings.presentation.component.openUrl
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.CloudOff
import compose.icons.feathericons.Github
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Package
import compose.icons.feathericons.Search
import compose.icons.feathericons.Server
import compose.icons.feathericons.ShoppingBag
import compose.icons.feathericons.Tag

/** 非官方徽标用语义色的 warning 槽位，跟随主题与暗色模式。 */
private val UnofficialBadgeBackground: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.semanticColors.warningContainer
private val UnofficialBadgeText: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.semanticColors.onWarningContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginStoreSection(
    onShowPreview: (PluginStorePreview) -> Unit,
    onOpenDetail: (PluginStoreItem) -> Unit,
    viewModel: PluginStoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val languageTag = currentLanguageTag()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var previousSourceId by rememberSaveable { mutableStateOf(state.selectedSourceId) }

    LaunchedEffect(state.selectedSourceId) {
        if (previousSourceId != state.selectedSourceId) {
            previousSourceId = state.selectedSourceId
            searchQuery = ""
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(state.preview) {
        state.preview?.let { preview ->
            onShowPreview(preview)
            viewModel.consumePreview()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.refreshResult.collect { failed ->
            val message = if (failed > 0) {
                context.getString(R.string.plugins_store_refresh_done_failed, failed)
            } else {
                context.getString(R.string.plugins_store_refresh_done)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val loadedCountsBySourceId = remember(state.items, state.loading) {
        if (state.loading) emptyMap() else state.items.groupingBy { it.source.id }.eachCount()
    }
    val showSourceOrigin = state.selectedSourceId == PluginIndexDefaults.ALL_SOURCES_ID
    val filteredItems = remember(state.items, searchQuery, languageTag) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            state.items
        } else {
            state.items.filter { item ->
                item.plugin.name.resolve(languageTag).lowercase().contains(query) ||
                    item.plugin.id.lowercase().contains(query) ||
                    item.plugin.author.lowercase().contains(query) ||
                    item.plugin.description.resolve(languageTag).lowercase().contains(query)
            }
        }
    }
    // 安装/下载错误归属具体插件，只在该插件详情页展示；整页错误只处理索引错误。
    val indexError = state.error?.takeIf { it.pluginId == null }

    Column(modifier = Modifier.fillMaxSize()) {
        val selectedSource = state.sources.firstOrNull { it.id == state.selectedSourceId }
            ?: PluginIndexDefaults.builtIns.first()
        SettingsGroup(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            SettingsRow(
                icon = FeatherIcons.Server,
                title = stringResource(
                R.string.plugin_sources_current,
                if (state.selectedSourceId == PluginIndexDefaults.ALL_SOURCES_ID) stringResource(R.string.muse_plugin_sources_all)
                else pluginSourceName(selectedSource)
            ),
                onClick = { showSources = true }
            )
        }
        if (state.rejectedPluginCount > 0) {
            Text(
                text = stringResource(R.string.plugin_sources_rejected, state.rejectedPluginCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xs)
            )
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f)
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            when {
                state.loading && state.items.isEmpty() -> item(key = "loading", contentType = "loading") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
                indexError != null && state.items.isEmpty() -> item(key = "error", contentType = "empty") {
                    StoreErrorState(
                        error = indexError,
                        onRetry = viewModel::refresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
                state.items.isEmpty() -> item(key = "empty", contentType = "empty") {
                    SettingsEmptyState(
                        icon = FeatherIcons.ShoppingBag,
                        title = stringResource(R.string.plugins_store_empty_title),
                        hint = stringResource(R.string.plugins_store_empty_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
                else -> {
                    if (state.items.size >= 3) {
                        item(key = "search", contentType = "search") {
                            ModelSearchField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                placeholder = stringResource(R.string.plugins_search_placeholder),
                                modifier = Modifier.padding(bottom = Spacing.xs)
                            )
                        }
                    }
                    if (filteredItems.isEmpty()) {
                        item(key = "search-empty", contentType = "empty") {
                            PluginSearchEmptyState()
                        }
                    } else {
                        itemsIndexed(
                            items = filteredItems,
                            key = { _, item -> "${item.source.id}:${item.plugin.id}" },
                            contentType = { _, _ -> "store-row" }
                        ) { index, item ->
                            LazyGroupItem(index = index, lastIndex = filteredItems.lastIndex) {
                                StorePluginRow(
                                    item = item,
                                    languageTag = languageTag,
                                    showSourceOrigin = showSourceOrigin,
                                    onClick = { onOpenDetail(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
    if (showSources) {
        PluginStoreSourcesSheet(
            sources = state.sources.ifEmpty { PluginIndexDefaults.builtIns },
            selectedSourceId = state.selectedSourceId,
            loadedCountsBySourceId = loadedCountsBySourceId,
            onSelect = viewModel::selectSource,
            onSaveCustomSources = viewModel::saveCustomSources,
            onDismiss = { showSources = false }
        )
    }
}

@Composable
private fun pluginVersionText(version: String?): String =
    version?.let { "v$it" } ?: stringResource(R.string.plugins_store_version_latest)

/** 商店列表与已安装列表同版式：图标 + 名称 + 描述；商店不需要开关，点击进入独立详情页。 */
@Composable
private fun StorePluginRow(
    item: PluginStoreItem,
    languageTag: String,
    showSourceOrigin: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FeatherIcons.Package,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.plugin.name.resolve(languageTag),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.installedVersion != null && !item.updateAvailable) {
                    Spacer(modifier = Modifier.width(6.dp))
                    McpPill(
                        text = stringResource(R.string.plugins_store_badge_installed),
                        textColor = MaterialTheme.colorScheme.tertiary,
                        backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                    )
                }
                if (item.updateAvailable) {
                    Spacer(modifier = Modifier.width(6.dp))
                    McpPill(
                        text = stringResource(R.string.plugins_store_badge_update),
                        textColor = MaterialTheme.colorScheme.primary,
                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                }
                if (!item.official) {
                    Spacer(modifier = Modifier.width(6.dp))
                    McpPill(
                        text = stringResource(R.string.plugins_store_badge_unofficial),
                        textColor = UnofficialBadgeText,
                        backgroundColor = UnofficialBadgeBackground
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.plugin.description.resolve(languageTag),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            if (showSourceOrigin) {
                Text(
                    text = stringResource(R.string.plugin_sources_item_origin, pluginSourceName(item.source)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun PluginStoreDetailSection(
    item: PluginStoreItem,
    onBack: () -> Unit,
    onShowPreview: (PluginStorePreview) -> Unit,
    viewModel: PluginStoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latest = state.items.firstOrNull {
        it.source.id == item.source.id && it.plugin.id == item.plugin.id
    } ?: item
    val context = LocalContext.current
    val languageTag = currentLanguageTag()
    val installing = state.installingPluginId == latest.plugin.id

    LaunchedEffect(state.preview, latest.plugin.id) {
        state.preview?.takeIf { it.preview.manifest.id == latest.plugin.id }?.let { preview ->
            onShowPreview(preview)
            viewModel.consumePreview()
        }
    }

    BackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
        ) {
            StoreHeaderCard(item = latest, languageTag = languageTag)
            SettingsGroupHeader(text = stringResource(R.string.plugins_section_info))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Tag,
                    title = stringResource(R.string.plugins_store_detail_version),
                    trailing = { StoreInfoValue(pluginVersionText(latest.plugin.version)) }
                )
                if (latest.plugin.tags.isNotEmpty()) {
                    SettingsDivider()
                    SettingsRow(
                        icon = FeatherIcons.Package,
                        title = stringResource(R.string.plugins_store_detail_tags),
                        trailing = { StoreInfoValue(latest.plugin.tags.joinToString(" · ")) }
                    )
                }
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Server,
                    title = stringResource(R.string.plugins_store_detail_index_source),
                    trailing = { StoreInfoValue(latest.source.name) }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Github,
                    title = stringResource(R.string.plugins_store_detail_source),
                    onClick = { openUrl(context, latest.plugin.repository) }
                )
                latest.plugin.license?.let { license ->
                    SettingsDivider()
                    SettingsRow(
                        icon = FeatherIcons.Book,
                        title = stringResource(R.string.plugins_store_detail_license),
                        trailing = { StoreInfoValue(license) }
                    )
                }
                latest.plugin.homepage?.let { homepage ->
                    SettingsDivider()
                    SettingsRow(
                        icon = FeatherIcons.Globe,
                        title = stringResource(R.string.plugins_store_detail_homepage),
                        onClick = { openUrl(context, homepage) }
                    )
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
            val installError = state.error?.takeIf {
                it.pluginId == latest.plugin.id &&
                    (it.code == PluginStoreErrorCode.DOWNLOAD_FAILED ||
                        it.code == PluginStoreErrorCode.NO_COMPATIBLE_RELEASE ||
                        it.code == PluginStoreErrorCode.PREVIEW_FAILED)
            }
            if (installError != null) {
                Text(
                    text = listOfNotNull(
                        storeErrorMessage(installError),
                        installError.detail?.takeIf { it.isNotBlank() }
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs)
                )
            }
            Button(
                onClick = { viewModel.install(latest) },
                enabled = latest.compatible && !installing &&
                    (latest.installedVersion == null || latest.updateAvailable),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                Text(
                    when {
                        installing -> stringResource(R.string.plugins_installing)
                        !latest.compatible -> latest.plugin.minAppVersion?.let { minimum ->
                            stringResource(R.string.plugins_store_requires_min_version, minimum)
                        } ?: stringResource(R.string.plugins_store_incompatible)
                        latest.installedVersion != null && !latest.updateAvailable ->
                            stringResource(R.string.plugins_store_installed)
                        latest.updateAvailable -> stringResource(R.string.plugins_store_update)
                        else -> stringResource(R.string.plugins_store_install)
                    }
                )
            }
        }
    }
}

@Composable
private fun StoreHeaderCard(item: PluginStoreItem, languageTag: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Package,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.plugin.name.resolve(languageTag),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!item.official) {
                        Spacer(modifier = Modifier.width(6.dp))
                        McpPill(
                            text = stringResource(R.string.plugins_store_badge_unofficial),
                            textColor = UnofficialBadgeText,
                            backgroundColor = UnofficialBadgeBackground
                        )
                    }
                }
                Text(
                    text = item.plugin.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val description = item.plugin.description.resolve(languageTag)
                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
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
private fun StoreInfoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 190.dp)
    )
}

/** 索引读取失败时的整页占位，与商店空态用同一套版式。 */
@Composable
private fun StoreErrorState(
    error: PluginStoreError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsEmptyState(
        icon = FeatherIcons.CloudOff,
        title = storeErrorMessage(error),
        hint = error.detail?.takeIf { it.isNotBlank() },
        actions = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.plugins_store_retry))
            }
        },
        modifier = modifier
    )
}

@Composable
private fun storeErrorMessage(error: PluginStoreError): String = when (error.code) {
    PluginStoreErrorCode.NETWORK -> stringResource(R.string.plugins_store_error_network)
    PluginStoreErrorCode.INVALID_INDEX -> stringResource(R.string.plugins_store_error_invalid)
    PluginStoreErrorCode.NO_COMPATIBLE_RELEASE -> stringResource(R.string.plugins_store_error_no_release)
    PluginStoreErrorCode.DOWNLOAD_FAILED -> stringResource(R.string.plugins_store_error_download)
    PluginStoreErrorCode.PREVIEW_FAILED -> error.pluginError?.let { pluginErrorMessage(it) }
        ?: stringResource(R.string.plugins_store_error_preview)
}
