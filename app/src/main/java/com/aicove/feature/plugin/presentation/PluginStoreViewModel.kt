package com.aicove.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicove.core.util.FileLogger
import com.aicove.feature.plugin.data.PluginRecord
import com.aicove.feature.plugin.data.PluginPackageReader
import com.aicove.feature.plugin.data.index.PluginIndexDefaults
import com.aicove.feature.plugin.domain.PluginInstallService
import com.aicove.feature.plugin.data.index.NoMatchingReleaseException
import com.aicove.feature.plugin.data.index.PluginIndexClient
import com.aicove.feature.plugin.data.index.PluginIndexException
import com.aicove.feature.plugin.data.index.PluginIndexPlugin
import com.aicove.feature.plugin.data.index.PluginIndexSource
import com.aicove.feature.plugin.data.index.PluginOfficialRepositories
import com.aicove.feature.plugin.data.index.PluginIndexSourceRepository
import com.aicove.feature.plugin.domain.PluginError
import com.aicove.feature.plugin.domain.PluginInstallOrigin
import com.aicove.feature.plugin.domain.PluginManager
import com.aicove.feature.plugin.domain.PluginPreview
import com.aicove.feature.plugin.domain.PluginPreviewResult
import com.aicove.feature.plugin.domain.SemVer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginStoreItem(
    val plugin: PluginIndexPlugin,
    val source: PluginIndexSource,
    val installedVersion: String?,
    val updateAvailable: Boolean,
    val compatible: Boolean,
    /** 插件源码仓库是否归属 AiCove 官方账号。 */
    val official: Boolean
)

data class PluginStorePreview(
    val preview: PluginPreview,
    val origin: PluginInstallOrigin,
    val expectedRepository: String?,
    val sourceLabel: String,
    val officialSource: Boolean
)

enum class PluginStoreErrorCode {
    NETWORK,
    INVALID_INDEX,
    NO_COMPATIBLE_RELEASE,
    DOWNLOAD_FAILED,
    PREVIEW_FAILED
}

data class PluginStoreError(
    val code: PluginStoreErrorCode,
    val detail: String? = null,
    val pluginError: PluginError? = null,
    /** 安装/下载错误所属的插件；索引错误为 null。 */
    val pluginId: String? = null
)

data class PluginStoreUiState(
    /** 首次读取来源前就显示加载态，避免先闪一下空列表。 */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val sources: List<PluginIndexSource> = emptyList(),
    val selectedSourceId: String? = null,
    val rejectedPluginCount: Int = 0,
    val items: List<PluginStoreItem> = emptyList(),
    /** 每个已安装插件可用的最高版本更新条目；供已安装页展示「可更新」。 */
    val updatesByPluginId: Map<String, PluginStoreItem> = emptyMap(),
    val error: PluginStoreError? = null,
    val installingPluginId: String? = null,
    val preview: PluginStorePreview? = null
)

@HiltViewModel
class PluginStoreViewModel @Inject constructor(
    private val client: PluginIndexClient,
    private val sourceRepository: PluginIndexSourceRepository,
    private val manager: PluginManager,
    private val installService: PluginInstallService
) : ViewModel() {

    private val _state = MutableStateFlow(PluginStoreUiState())
    val state: StateFlow<PluginStoreUiState> = _state.asStateFlow()

    /** 一次加载结束后要提示的失败来源数；仅在用户下拉刷新或确实有来源失败时发送。 */
    private val _refreshResult = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val refreshResult: SharedFlow<Int> = _refreshResult.asSharedFlow()

    // 同地址的不同来源也必须隔离；抓取时间戳不属于加载身份。
    private var loadedSources: List<Pair<String, String>>? = null
    private var loadJob: Job? = null
    private var loadToken = 0

    init {
        viewModelScope.launch {
            sourceRepository.selection.collect { selection ->
                val selected = if (selection.selectedSourceId == PluginIndexDefaults.ALL_SOURCES_ID) {
                    selection.sources
                } else selection.sources.filter { it.id == selection.selectedSourceId }
                val key = selected.map { it.id to it.indexUrl }
                val changed = key != loadedSources
                _state.update {
                    it.copy(
                        sources = selection.sources,
                        selectedSourceId = selection.selectedSourceId,
                        items = if (changed) emptyList() else it.items,
                        updatesByPluginId = if (changed) emptyMap() else it.updatesByPluginId,
                        error = if (changed) null else it.error,
                        rejectedPluginCount = if (changed) 0 else it.rejectedPluginCount
                    )
                }
                if (changed) {
                    loadedSources = key
                    loadSelected(force = false)
                }
            }
        }
        viewModelScope.launch {
            manager.plugins.collect { installed ->
                val records = installed.associate { it.id to it.record }
                _state.update { current ->
                    val updatedItems = current.items.map { item ->
                        val record = records[item.plugin.id]
                        item.copy(
                            installedVersion = record?.version,
                            updateAvailable = hasUpdate(item.plugin, item.source, record)
                        )
                    }
                    current.copy(
                        items = updatedItems,
                        updatesByPluginId = updatesOf(updatedItems)
                    )
                }
            }
        }
    }

    fun refresh() {
        loadSelected(force = true)
    }

    suspend fun selectSource(id: String): Result<Unit> = try {
        sourceRepository.selectSource(id)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun saveCustomSources(text: String): Result<Unit> =
        sourceRepository.setCustomSources(text.lines())

    private fun loadSelected(force: Boolean) {
        val token = ++loadToken
        loadJob?.cancel()
        val sources = if (_state.value.selectedSourceId == PluginIndexDefaults.ALL_SOURCES_ID) {
            _state.value.sources
        } else _state.value.sources.filter { it.id == _state.value.selectedSourceId }
        if (sources.isEmpty()) {
            _state.update { it.copy(loading = false, refreshing = false) }
            return
        }
        _state.update { it.copy(loading = true, refreshing = it.items.isNotEmpty(), error = null) }
        loadJob = viewModelScope.launch {
            try {
                var failedSources = 0
                var loaded = false
                var rejected = 0
                var lastError: Exception? = null
                val items = mutableListOf<PluginStoreItem>()
                for (source in sources) {
                    try {
                        val snapshot = client.fetchIndex(source, forceRefresh = force)
                        if (token != loadToken) return@launch
                        if (!snapshot.stale) sourceRepository.markFetched(source.id, snapshot.fetchedAt)
                        else failedSources++
                        rejected += snapshot.index.rejectedPluginCount
                        val installed = manager.plugins.value.associate { it.id to it.record }
                        items += snapshot.index.plugins.map { plugin ->
                            val record = installed[plugin.id]
                            PluginStoreItem(
                                plugin = plugin, source = source,
                                installedVersion = record?.version,
                                updateAvailable = hasUpdate(plugin, source, record),
                                compatible = client.isCompatible(plugin),
                                official = source.official && PluginOfficialRepositories.isOfficial(plugin.repository)
                            )
                        }
                        loaded = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failedSources++
                        lastError = e
                        FileLogger.w(TAG, "读取插件索引失败", e)
                    }
                }
                if (token != loadToken) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        items = if (loaded) items else it.items,
                        updatesByPluginId = if (loaded) updatesOf(items) else it.updatesByPluginId,
                        rejectedPluginCount = if (loaded) rejected else it.rejectedPluginCount,
                        error = lastError?.takeIf { items.isEmpty() }?.let { error ->
                            PluginStoreError(
                                if (error is PluginIndexException) PluginStoreErrorCode.INVALID_INDEX else PluginStoreErrorCode.NETWORK,
                                detail = error.message
                            )
                        }
                    )
                }
                if (force || failedSources > 0) _refreshResult.tryEmit(failedSources)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token != loadToken) return@launch
                FileLogger.w(TAG, "读取插件索引失败", e)
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = PluginStoreError(
                            code = if (e is PluginIndexException) {
                                PluginStoreErrorCode.INVALID_INDEX
                            } else {
                                PluginStoreErrorCode.NETWORK
                            },
                            detail = e.message
                        )
                    )
                }
                _refreshResult.tryEmit(1)
            }
        }
    }

    fun install(item: PluginStoreItem) {
        if (!item.compatible) return
        if (_state.value.installingPluginId != null) return
        _state.update { it.copy(installingPluginId = item.plugin.id, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val asset = client.resolveRelease(item.plugin)
                val packageFile = client.download(asset)
                val result = packageFile.file.inputStream().use { manager.preview(it) }
                when (result) {
                    is PluginPreviewResult.Ready -> {
                        val preview = result.preview
                        val archiveSha256 = PluginPackageReader.sha256Hex(packageFile.file.readBytes())
                        val mismatchedFields = storePackageMismatches(item.plugin, preview.manifest, asset.version, archiveSha256)
                        if (mismatchedFields.isNotEmpty()) {
                            manager.discard(preview.token)
                            _state.update {
                                it.copy(
                                    installingPluginId = null,
                                    error = PluginStoreError(
                                        code = PluginStoreErrorCode.PREVIEW_FAILED,
                                        detail = "Manifest mismatch: ${mismatchedFields.joinToString()}",
                                        pluginId = item.plugin.id
                                    )
                                )
                            }
                            return@launch
                        }
                        val official = isVerifiedOfficialPackage(item.source, item.plugin, preview.manifest.repository)
                        val expectedRepository = if (official) null else item.plugin.repository
                        _state.update {
                            it.copy(
                                installingPluginId = null,
                                preview = PluginStorePreview(
                                    preview = preview,
                                    origin = PluginInstallOrigin(
                                        kind = PluginInstallOrigin.KIND_INDEX,
                                        sourceId = item.source.id,
                                        sourceName = item.source.name,
                                        indexUrl = item.source.indexUrl,
                                        repository = item.plugin.repository,
                                        official = official
                                    ),
                                    expectedRepository = expectedRepository,
                                    sourceLabel = item.source.name,
                                    officialSource = official
                                )
                            )
                        }
                    }
                    is PluginPreviewResult.Failed -> _state.update {
                        it.copy(
                            installingPluginId = null,
                            error = PluginStoreError(
                                code = PluginStoreErrorCode.PREVIEW_FAILED,
                                pluginError = result.errors.firstOrNull(),
                                pluginId = item.plugin.id
                            )
                        )
                    }
                }
            } catch (e: NoMatchingReleaseException) {
                _state.update {
                    it.copy(
                        installingPluginId = null,
                        error = PluginStoreError(
                            code = PluginStoreErrorCode.NO_COMPATIBLE_RELEASE,
                            detail = e.message,
                            pluginId = item.plugin.id
                        )
                    )
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "下载插件失败: ${item.plugin.id}", e)
                _state.update {
                    it.copy(
                        installingPluginId = null,
                        error = PluginStoreError(
                            code = PluginStoreErrorCode.DOWNLOAD_FAILED,
                            detail = e.message,
                            pluginId = item.plugin.id
                        )
                    )
                }
            }
        }
    }

    fun consumePreview() {
        _state.update { it.copy(preview = null) }
    }

    fun dismissPreview() {
        val preview = _state.value.preview ?: return
        _state.update { it.copy(preview = null) }
        viewModelScope.launch(Dispatchers.IO) { manager.discard(preview.preview.token) }
    }

    fun confirmInstall() {
        val preview = _state.value.preview ?: return
        if (_state.value.installingPluginId != null) return
        _state.update { it.copy(installingPluginId = preview.preview.manifest.id, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = installService.commit(preview.preview, preview.origin)) {
                is com.aicove.feature.plugin.domain.PluginInstallResult.Installed -> {
                    _state.update { current ->
                        val version = preview.preview.manifest.version.toString()
                        val installedItems = current.items.map { item ->
                            if (item.plugin.id == result.pluginId) {
                                item.copy(
                                    installedVersion = version,
                                    updateAvailable = item.source.id == preview.origin.sourceId &&
                                        item.source.indexUrl == preview.origin.indexUrl &&
                                        isNewer(item.plugin.version, version)
                                )
                            } else item
                        }
                        current.copy(
                            items = installedItems,
                            updatesByPluginId = updatesOf(installedItems),
                            preview = null,
                            installingPluginId = null,
                            error = null
                        )
                    }
                }
                is com.aicove.feature.plugin.domain.PluginInstallResult.Failed -> {
                    _state.update {
                        it.copy(
                            installingPluginId = null,
                            error = PluginStoreError(
                                code = PluginStoreErrorCode.PREVIEW_FAILED,
                                pluginError = result.error,
                                pluginId = preview.preview.manifest.id
                            )
                        )
                    }
                }
            }
        }
    }

    private fun isNewer(candidate: String?, installed: String?): Boolean {
        if (candidate == null || installed == null) return false
        val left = SemVer.parse(candidate) ?: return false
        val right = SemVer.parse(installed) ?: return false
        return left > right
    }

    private fun hasUpdate(plugin: PluginIndexPlugin, source: PluginIndexSource, record: PluginRecord?): Boolean =
        record != null && record.sourceId == source.id && record.indexUrl == source.indexUrl &&
            isNewer(plugin.version, record.version)

    private fun updatesOf(items: List<PluginStoreItem>): Map<String, PluginStoreItem> =
        items.asSequence()
            .filter { it.updateAvailable && it.compatible }
            .groupBy { it.plugin.id }
            .mapValues { (_, candidates) ->
                candidates.maxByOrNull { item ->
                    item.plugin.version?.let(SemVer::parse) ?: SemVer(0, 0, 0)
                }!!
            }

    private companion object {
        const val TAG = "PluginStoreViewModel"
    }
}
