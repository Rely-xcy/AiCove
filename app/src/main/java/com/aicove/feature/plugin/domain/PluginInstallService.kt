package com.aicove.feature.plugin.domain

import com.aicove.core.util.FileLogger
import com.aicove.feature.plugin.data.PluginBackupPermissionRepository
import com.aicove.feature.plugin.data.PluginDevPackageBuilder
import com.aicove.feature.plugin.data.PluginOverlayPermissionRepository
import com.aicove.feature.plugin.data.PluginShizukuPermissionRepository
import com.aicove.feature.plugin.overlay.PluginOverlayManager
import com.aicove.feature.plugin.runtime.PluginHookDispatcher
import com.aicove.feature.plugin.runtime.PluginProviderRegistrar
import com.aicove.feature.plugin.runtime.PluginRuntimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 插件安装的唯一入口，把「预览 → 提交 → 安装后副作用」收成一处。
 *
 * 界面与 AI 工具都经这里安装：装配 Provider、派发安装钩子、来源变化时撤销高危授权，
 * 这些步骤漏一步就会留下「装了但没生效」的中间态。
 */
@Singleton
class PluginInstallService @Inject constructor(
    private val pluginManager: PluginManager,
    private val providerRegistrar: PluginProviderRegistrar,
    private val hookDispatcher: PluginHookDispatcher,
    private val shizukuPermissionRepository: PluginShizukuPermissionRepository,
    private val overlayPermissionRepository: PluginOverlayPermissionRepository,
    private val overlayManager: PluginOverlayManager,
    private val backupPermissionRepository: PluginBackupPermissionRepository,
    private val runtimeManager: PluginRuntimeManager
) {

    /** 打包开发目录里的项目并安装；[dirName] 必须是 devDir 的直接子目录名。 */
    suspend fun installDevProject(dirName: String): PluginInstallResult = withContext(Dispatchers.IO) {
        val devDir = pluginManager.devDir
        val source = File(devDir, dirName)
        if (dirName.isBlank() || dirName == "." || dirName == ".." ||
            dirName.contains('/') || dirName.contains('\\') ||
            source.canonicalFile.parentFile != devDir.canonicalFile || !source.isDirectory
        ) {
            return@withContext failure(PluginErrorCode.ARCHIVE_UNREADABLE)
        }
        val zip = File(devDir, ".$dirName.zip")
        try {
            PluginDevPackageBuilder.zip(source, zip)
            installArchive(zip)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "安装开发插件失败: $dirName", e)
            failure(PluginErrorCode.ARCHIVE_UNREADABLE)
        } finally {
            zip.delete()
        }
    }

    /** 从本地插件包安装。 */
    suspend fun installArchive(
        archive: File,
        origin: PluginInstallOrigin = PluginInstallOrigin()
    ): PluginInstallResult = withContext(Dispatchers.IO) {
        if (!archive.isFile) return@withContext failure(PluginErrorCode.ARCHIVE_UNREADABLE)
        val preview = when (val result = archive.inputStream().use { pluginManager.preview(it) }) {
            is PluginPreviewResult.Failed ->
                return@withContext PluginInstallResult.Failed(
                    result.errors.firstOrNull() ?: PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)
                )
            is PluginPreviewResult.Ready -> result.preview
        }
        commit(preview, origin)
    }

    /** 提交一个已经预览过的插件包（界面预览弹层使用）。 */
    suspend fun commit(
        preview: PluginPreview,
        origin: PluginInstallOrigin = PluginInstallOrigin()
    ): PluginInstallResult = withContext(Dispatchers.IO) {
        val previousVersion = preview.installedVersion
        withContext(NonCancellable) {
        when (val result = pluginManager.install(preview.token, origin)) {
            is PluginInstallResult.Failed -> result
            is PluginInstallResult.Installed -> {
                // 来源变了就不沿用旧包拿到的高危授权。
                if (sourceChanged(preview, origin)) {
                    shizukuPermissionRepository.revoke(result.pluginId)
                    overlayPermissionRepository.revoke(result.pluginId)
                    backupPermissionRepository.revoke(result.pluginId)
                }
                runtimeManager.closePlugin(result.pluginId)
                overlayManager.closePlugin(result.pluginId)
                runCatching { providerRegistrar.sync(result.pluginId) }
                    .onFailure { FileLogger.e(TAG, "同步插件提供商失败: ${result.pluginId}", it) }
                hookDispatcher.dispatchPluginInstalled(result.pluginId, previousVersion)
                result
            }
        }
        }
    }

    internal fun sourceChanged(preview: PluginPreview, origin: PluginInstallOrigin): Boolean {
        if (preview.installedVersion == null) return false
        val installedFromIndex = preview.installedSourceId != null
        val installFromIndex = origin.kind == PluginInstallOrigin.KIND_INDEX
        return installedFromIndex != installFromIndex ||
            preview.installedSourceId != origin.sourceId ||
            preview.installedRepository != origin.repository ||
            (preview.installedOfficial ?: false) != origin.official
    }

    private fun failure(code: PluginErrorCode) = PluginInstallResult.Failed(PluginError(code))

    private companion object {
        const val TAG = "PluginInstallService"
    }
}
