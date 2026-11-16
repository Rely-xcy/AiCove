package com.aicove.feature.plugin.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicove.R
import com.aicove.core.util.FileLogger
import com.aicove.feature.agent.domain.container.CommandEngine
import com.aicove.feature.agent.domain.shizuku.ShizukuManager
import com.aicove.feature.agent.domain.shizuku.ShizukuState
import com.aicove.feature.plugin.data.PluginDevPackageBuilder
import com.aicove.feature.plugin.data.PluginDevSettingsRepository
import com.aicove.feature.plugin.data.PluginShizukuPermissionRepository
import com.aicove.core.plugin.PluginSlotEntry
import com.aicove.feature.plugin.domain.PluginManifestParser
import com.aicove.feature.plugin.domain.ManifestParseResult
import com.aicove.feature.plugin.domain.InstalledPlugin
import com.aicove.feature.plugin.domain.LocalizedText
import com.aicove.feature.plugin.domain.PluginError
import com.aicove.feature.plugin.domain.PluginErrorCode
import com.aicove.feature.plugin.domain.PluginInstallResult
import com.aicove.feature.plugin.domain.PluginManager
import com.aicove.feature.plugin.domain.PluginPreview
import com.aicove.feature.plugin.domain.PluginPreviewResult
import com.aicove.feature.plugin.domain.PluginSettingsPage
import com.aicove.feature.plugin.runtime.PluginDependencyResult
import com.aicove.feature.plugin.runtime.PluginEventBus
import com.aicove.feature.plugin.runtime.PluginProviderRegistrar
import com.aicove.feature.plugin.runtime.PluginRuntimeManager
import com.aicove.feature.plugin.runtime.PluginShizukuRunner
import com.aicove.feature.plugin.runtime.PluginTerminalRunner
import com.aicove.feature.plugin.runtime.PluginChatService
import com.aicove.feature.plugin.runtime.PluginTipManager
import com.aicove.feature.workspace.data.repository.WorkspaceRepository
import com.aicove.feature.workspace.domain.FileAccessProvider
import com.aicove.feature.workspace.domain.PathHomeResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 导入插件包的界面状态。 */
sealed interface PluginImportState {
    data object Idle : PluginImportState

    data object Reading : PluginImportState

    /**
     * 包已通过校验，等待使用者确认。
     */
    data class Preview(
        val preview: PluginPreview,
        val installing: Boolean = false,
        val error: PluginError? = null,
        /** 开发者侧载来源目录名；普通 zip 导入为 null。 */
        val sourceDirName: String? = null
    ) : PluginImportState

    data class Failed(val errors: List<PluginError>) : PluginImportState
}

/** 依赖准备的界面状态；按插件 ID 隔离。 */
data class PluginDependencyUiState(
    val running: Boolean = false,
    val ready: Boolean? = null,
    val message: String? = null,
    val installRevision: String? = null
)

/** 开发者目录里的一个插件项目；安装/重载按目录重新打包。 */
data class PluginDevProject(
    val dirName: String,
    val pluginId: String,
    val version: String,
    val installedVersion: String?,
    val lastModified: Long,
    val error: String? = null,
    val name: LocalizedText? = null,
    val description: LocalizedText? = null,
    val publisher: String? = null
)

@HiltViewModel
class PluginsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: PluginManager,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
    private val commandEngine: CommandEngine,
    private val eventBus: PluginEventBus,
    private val pluginRuntimeManager: PluginRuntimeManager,
    private val pluginTerminalRunner: PluginTerminalRunner,
    private val pluginProviderRegistrar: PluginProviderRegistrar,
    private val shizukuPermissionRepository: PluginShizukuPermissionRepository,
    private val shizukuManager: ShizukuManager,
    private val shizukuRunner: PluginShizukuRunner,
    private val tipManager: PluginTipManager,
    private val chatService: PluginChatService,
    private val devSettingsRepository: PluginDevSettingsRepository
) : ViewModel() {

    val plugins: StateFlow<List<InstalledPlugin>> = manager.plugins

    /** 当前系统语言；插件标题按它解析。ViewModel 里不能调 Composable 的 currentLanguageTag()。 */
    private val languageTagFlow = MutableStateFlow(
        context.resources.configuration.locales[0]?.toLanguageTag().orEmpty().ifBlank { "zh-CN" }
    )

    /**
     * 按槽位取已启用插件的入口，供侧栏与顶栏菜单渲染。
     *
     * 只取启用中的入口；标题在这里就按当前语言解析好，
     * 因为 core 层的 [PluginSlotEntry] 不能依赖 feature/plugin 的 LocalizedText。
     */
    fun slotEntries(slot: String): StateFlow<List<PluginSlotEntry>> =
        combine(manager.plugins, languageTagFlow) { plugins, languageTag ->
            plugins
                .filter { it.isActive }
                .flatMap { plugin ->
                    plugin.content.uiEntries
                        .filter { it.slot == slot }
                        .map { entry ->
                            PluginSlotEntry(
                                pluginId = entry.pluginId,
                                localId = entry.localId,
                                title = entry.title.resolve(languageTag),
                                icon = entry.icon
                            )
                        }
                }
                .take(3)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val loaded: StateFlow<Boolean> = manager.isLoaded

    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state

    /** 已授权的插件；停用/卸载后自动从集合中消失。 */
    val shizukuGrantedPluginIds: StateFlow<Set<String>> =
        combine(manager.plugins, shizukuPermissionRepository.grants) { plugins, grants ->
            plugins.filter { plugin ->
                plugin.isActive && plugin.content.shizuku != null && grants.containsKey(plugin.id)
            }.map { it.id }.toSet()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _importState = MutableStateFlow<PluginImportState>(PluginImportState.Idle)
    val importState: StateFlow<PluginImportState> = _importState.asStateFlow()

    private val _busyPluginIds = MutableStateFlow<Set<String>>(emptySet())
    val busyPluginIds: StateFlow<Set<String>> = _busyPluginIds.asStateFlow()

    private val operationMessageChannel = Channel<Int>(Channel.BUFFERED)
    val operationMessages: Flow<Int> = operationMessageChannel.receiveAsFlow()

    private val _dependencyStates = MutableStateFlow<Map<String, PluginDependencyUiState>>(emptyMap())
    val dependencyStates: StateFlow<Map<String, PluginDependencyUiState>> = _dependencyStates.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _devRefreshing = MutableStateFlow(false)
    val devRefreshing: StateFlow<Boolean> = _devRefreshing.asStateFlow()

    private val _devMode = MutableStateFlow(false)
    val devMode: StateFlow<Boolean> = _devMode.asStateFlow()

    /** 开发者目录固定挂在容器 `~/.aicode/plugin-dev`，每个子目录是一个插件项目。 */
    val devRootPath: String = "~/.aicode/plugin-dev"

    private val _devProjects = MutableStateFlow<List<PluginDevProject>>(emptyList())
    val devProjects: StateFlow<List<PluginDevProject>> = _devProjects.asStateFlow()

    private var devWatcherJob: Job? = null
    private val devLastLoaded = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch(Dispatchers.IO) { manager.ensureLoaded() }
        viewModelScope.launch {
            devSettingsRepository.enabled.collect { enabled ->
                _devMode.value = enabled
                if (enabled) {
                    refreshDevProjects()
                    startDevWatcher()
                } else {
                    devWatcherJob?.cancel()
                    devWatcherJob = null
                    _devProjects.value = emptyList()
                }
            }
        }
    }

    fun import(uri: Uri) {
        if (_importState.value !is PluginImportState.Idle && _importState.value !is PluginImportState.Failed) return
        _importState.value = PluginImportState.Reading
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                context.contentResolver.openInputStream(uri)?.use { manager.preview(it) }
                    ?: PluginPreviewResult.Failed(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
            } catch (e: IOException) {
                FileLogger.w(TAG, "打开插件包失败", e)
                PluginPreviewResult.Failed(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
            } catch (e: SecurityException) {
                FileLogger.w(TAG, "没有读取插件包的权限", e)
                PluginPreviewResult.Failed(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
            }
            _importState.value = when (result) {
                is PluginPreviewResult.Ready -> PluginImportState.Preview(
                    preview = result.preview
                )
                is PluginPreviewResult.Failed -> PluginImportState.Failed(result.errors)
            }
        }
    }

    fun confirmInstall() {
        val state = _importState.value as? PluginImportState.Preview ?: return
        if (state.installing) return
        _importState.value = state.copy(installing = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = manager.install(state.preview.token)) {
                    is PluginInstallResult.Installed -> {
                        syncProviders(result.pluginId)
                        _importState.value = PluginImportState.Idle
                        operationMessageChannel.send(R.string.plugins_operation_installed)
                        state.sourceDirName?.let { dirName ->
                            devLastLoaded[dirName] = devDirModified(dirName)
                            refreshDevProjects()
                        }
                    }
                    is PluginInstallResult.Failed -> _importState.update { current ->
                        (current as? PluginImportState.Preview)?.copy(installing = false, error = result.error) ?: current
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "安装插件失败", e)
                _importState.update { current ->
                    (current as? PluginImportState.Preview)?.copy(
                        installing = false,
                        error = PluginError(PluginErrorCode.STORAGE_FAILED)
                    ) ?: current
                }
            }
        }
    }

    /** 关闭预览 / 错误提示；预览中的暂存包同时丢弃。 */
    fun dismissImport() {
        val state = _importState.value
        if (state is PluginImportState.Preview && state.installing) return
        _importState.value = PluginImportState.Idle
        if (state is PluginImportState.Preview) {
            viewModelScope.launch { manager.discard(state.preview.token) }
        }
    }

    fun setDevMode(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { devSettingsRepository.setEnabled(enabled) }
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                manager.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "刷新插件失败", e)
                operationMessageChannel.send(R.string.plugins_operation_refresh_failed)
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun refreshDevProjects() {
        if (_devRefreshing.value) return
        _devRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _devProjects.value = scanDevProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "刷新开发插件失败", e)
                operationMessageChannel.send(R.string.plugins_operation_refresh_failed)
            } finally {
                _devRefreshing.value = false
            }
        }
    }

    fun setDevProjectEnabled(project: PluginDevProject, enabled: Boolean) {
        val installed = manager.plugins.value.firstOrNull { it.id == project.pluginId }
        if (installed != null) setEnabled(installed.id, enabled)
        else if (enabled) installDevProject(project)
    }

    /** 从开发目录打 zip 后走现有预览流程，安装仍需使用者在预览里确认。 */
    fun installDevProject(project: PluginDevProject) {
        if (_importState.value !is PluginImportState.Idle && _importState.value !is PluginImportState.Failed) return
        _importState.value = PluginImportState.Reading
        viewModelScope.launch(Dispatchers.IO) {
            val zip = File(context.cacheDir, "plugin-dev-${project.dirName}.zip")
            val result = try {
                PluginDevPackageBuilder.zip(File(manager.devDir, project.dirName), zip)
                zip.inputStream().use { manager.preview(it) }
            } catch (e: Exception) {
                FileLogger.w(TAG, "打包开发插件失败: ${project.dirName}", e)
                PluginPreviewResult.Failed(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
            } finally {
                zip.delete()
            }
            _importState.value = when (result) {
                is PluginPreviewResult.Ready -> PluginImportState.Preview(
                    preview = result.preview,
                    sourceDirName = project.dirName
                )
                is PluginPreviewResult.Failed -> PluginImportState.Failed(result.errors)
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(_busyPluginIds) {
            if (id in _busyPluginIds.value) return
            _busyPluginIds.update { it + id }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val changed = manager.setEnabled(id, enabled)
                if (changed) {
                    if (enabled) {
                        syncProviders(id)
                    } else {
                        removeProviders(id)
                        pluginTerminalRunner.closePlugin(id)
                        tipManager.dismissPlugin(id)
                    }
                }
                operationMessageChannel.send(
                    if (!changed) R.string.plugins_operation_toggle_failed
                    else if (enabled) R.string.plugins_operation_enabled
                    else R.string.plugins_operation_disabled
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "切换插件状态失败: $id", e)
                operationMessageChannel.send(R.string.plugins_operation_toggle_failed)
            } finally {
                _busyPluginIds.update { it - id }
            }
        }
    }

    fun uninstall(id: String) {
        synchronized(_busyPluginIds) {
            if (id in _busyPluginIds.value) return
            _busyPluginIds.update { it + id }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uninstalled = try {
                    manager.uninstall(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.e(TAG, "卸载插件失败: $id", e)
                    false
                }
                val removed = manager.plugins.value.none { it.id == id }
                if (removed) {
                    removeProviders(id)
                    pluginTerminalRunner.closePlugin(id)
                    tipManager.dismissPlugin(id)
                    shizukuPermissionRepository.revoke(id)
                }
                operationMessageChannel.send(
                    if (!removed) R.string.plugins_operation_uninstall_failed
                    else if (uninstalled) R.string.plugins_operation_uninstalled
                    else R.string.plugins_operation_uninstall_cleanup_failed
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "清理插件资源失败: $id", e)
                operationMessageChannel.send(
                    if (manager.plugins.value.none { it.id == id }) R.string.plugins_operation_uninstall_cleanup_failed
                    else R.string.plugins_operation_uninstall_failed
                )
            } finally {
                _busyPluginIds.update { it - id }
            }
        }
    }

    /** 插件级 Shizuku 授权；关闭后该插件无法再通过 Shizuku 执行命令。 */
    fun setShizukuGranted(plugin: InstalledPlugin, granted: Boolean) {
        if (plugin.content.shizuku == null) return
        viewModelScope.launch(Dispatchers.IO) {
            if (granted) {
                shizukuPermissionRepository.grant(plugin.id)
            } else {
                shizukuPermissionRepository.revoke(plugin.id)
            }
        }
    }

    /** 提供商注册失败不影响安装/启停本身；插件页重新切换一次即可重试。 */
    private suspend fun syncProviders(pluginId: String) {
        try {
            pluginProviderRegistrar.sync(pluginId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "同步插件提供商失败: $pluginId", e)
        }
    }

    private suspend fun removeProviders(pluginId: String) {
        try {
            pluginProviderRegistrar.removeAll(pluginId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "移除插件提供商失败: $pluginId", e)
        }
    }

    /** 扫描开发目录：每个含 plugin.json 的子目录视为一个项目。 */
    private fun scanDevProjects(): List<PluginDevProject> {
        manager.ensureLoaded()
        val installed = manager.plugins.value.associate { it.id to it.record.version }
        return manager.devDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { dir ->
                val manifestFile = File(dir, "plugin.json")
                val parsed = runCatching {
                    if (manifestFile.isFile) PluginManifestParser.parse(manifestFile.readText()) else null
                }.getOrNull()
                val lastModified = dir.walkTopDown().filter { it.isFile }
                    .maxOfOrNull { it.lastModified() } ?: 0L
                when (parsed) {
                    is ManifestParseResult.Success -> PluginDevProject(
                        dirName = dir.name,
                        pluginId = parsed.manifest.id,
                        version = parsed.manifest.version.toString(),
                        installedVersion = installed[parsed.manifest.id],
                        lastModified = lastModified,
                        name = parsed.manifest.name,
                        description = parsed.manifest.description,
                        publisher = parsed.manifest.publisher
                    )
                    else -> PluginDevProject(
                        dirName = dir.name,
                        pluginId = "",
                        version = "",
                        installedVersion = null,
                        lastModified = lastModified,
                        error = "manifest"
                    )
                }
            }
            .sortedBy { it.dirName }
    }

    /** 开发者模式开启期间轮询目录 mtime；已安装项目变化时自动重新打包替换。 */
    private fun startDevWatcher() {
        if (devWatcherJob?.isActive == true) return
        devWatcherJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _devMode.value) {
                delay(DEV_POLL_MS)
                if (!_devMode.value) break
                val projects = scanDevProjects()
                _devProjects.value = projects
                projects.forEach { project ->
                    if (project.error != null) return@forEach
                    val baseline = devLastLoaded[project.dirName]
                    val versionChanged = project.installedVersion != null &&
                        project.installedVersion != project.version
                    if (baseline == null && !versionChanged) {
                        if (project.installedVersion != null) devLastLoaded[project.dirName] = project.lastModified
                        return@forEach
                    }
                    if (project.installedVersion != null && (versionChanged || project.lastModified > (baseline ?: 0L))) {
                        if (reloadDevProject(project)) {
                            devLastLoaded[project.dirName] = project.lastModified
                            FileLogger.i(TAG, "开发插件已重载: ${project.dirName}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun reloadDevProject(project: PluginDevProject): Boolean {
        val zip = File(context.cacheDir, "plugin-dev-${project.dirName}.zip")
        return try {
            PluginDevPackageBuilder.zip(File(manager.devDir, project.dirName), zip)
            val preview = zip.inputStream().use { manager.preview(it) }
            if (preview !is PluginPreviewResult.Ready) return false
            if (preview.preview.manifest.id != project.pluginId) return false
            if (preview.preview.installedVersion == null) return false
            when (val result = manager.install(preview.preview.token)) {
                is PluginInstallResult.Installed -> {
                    syncProviders(result.pluginId)
                    true
                }
                is PluginInstallResult.Failed -> false
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "自动重载开发插件失败: ${project.dirName}", e)
            false
        } finally {
            zip.delete()
        }
    }

    private fun devDirModified(dirName: String): Long =
        File(manager.devDir, dirName).walkTopDown().filter { it.isFile }
            .maxOfOrNull { it.lastModified() } ?: 0L

    /** 准备依赖：在本地容器、插件安装目录执行；同一插件不并发启动两次。 */
    fun prepareDependencies(id: String) {
        if (id in _busyPluginIds.value) return
        val record = manager.plugins.value.firstOrNull { it.id == id }?.record ?: return
        val runningState = PluginDependencyUiState(running = true, installRevision = record.installRevision)
        synchronized(_dependencyStates) {
            val current = _dependencyStates.value[id]
            if (current?.running == true && current.installRevision == record.installRevision) return
            _dependencyStates.update { it + (id to runningState) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = when (val result = manager.prepareDependencies(id)) {
                    is PluginDependencyResult.Ready -> runningState.copy(running = false, ready = true)
                    is PluginDependencyResult.MissingRuntime ->
                        runningState.copy(running = false, ready = false, message = result.message)
                    is PluginDependencyResult.Failed -> runningState.copy(
                        running = false,
                        ready = false,
                        message = if (result.code == "STATE_NOT_SAVED") {
                            context.getString(R.string.plugins_dependencies_state_not_saved)
                        } else result.message
                    )
                }
                val current = manager.plugins.value.firstOrNull { it.id == id }?.record
                val sameInstallation = current != null && if (record.installRevision != null) {
                    current.installRevision == record.installRevision
                } else {
                    current.installRevision == null && current.installedAt == record.installedAt &&
                        current.archiveSha256 == record.archiveSha256 && current.manifestSha256 == record.manifestSha256
                }
                _dependencyStates.update {
                    if (it[id] === runningState && sameInstallation) it + (id to state) else it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "准备插件依赖失败: $id", e)
                _dependencyStates.update {
                    if (it[id] === runningState) it + (id to runningState.copy(running = false, ready = false, message = e.message))
                    else it
                }
            } finally {
                _dependencyStates.update {
                    if (it[id] === runningState) it + (id to runningState.copy(running = false)) else it
                }
            }
        }
    }

    /** 插件专属数据目录；设置页入口与桥必须使用同一落点。 */
    fun dataDirFor(pluginId: String): File = manager.dataDirFor(pluginId)

    /** 为插件设置页构造宿主桥；桥与 WebView 一起缓存，由 [PluginWebViewCache] 统一释放。 */
    fun createWebBridge(
        page: PluginSettingsPage,
        pluginVersion: String,
        languageTag: String,
        theme: () -> String,
        onClosePage: () -> Unit,
        post: (String) -> Unit
    ): PluginWebBridge {
        val workspaceName = workspaceRepository.currentPath()
            .trimEnd('/')
            .substringAfterLast('/')
            .ifBlank { "workspace" }
        return PluginWebBridge(
            context = context,
            page = page,
            pluginVersion = pluginVersion,
            languageTag = languageTag,
            workspaceName = workspaceName,
            fileAccess = fileAccess,
            homeResolver = pathHomeResolver,
            commandEngine = commandEngine,
            workspacePath = { workspaceRepository.currentPath() },
            eventBus = eventBus,
            pluginManager = manager,
            pluginRuntimeManager = pluginRuntimeManager,
            terminalRunner = pluginTerminalRunner,
            shizukuRunner = shizukuRunner,
            tipManager = tipManager,
            chatService = chatService,
            workspacesProvider = { workspaceRepository.workspaces.value },
            theme = theme,
            onClosePage = onClosePage,
            post = post
        )
    }

    private companion object {
        const val TAG = "PluginsViewModel"
        const val DEV_POLL_MS = 2_000L
    }
}
