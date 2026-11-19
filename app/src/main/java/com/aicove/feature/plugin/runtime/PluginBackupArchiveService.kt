package com.aicove.feature.plugin.runtime

import com.aicove.core.plugin.BackupExtensionSource
import com.aicove.core.plugin.BackupExtensionSource.Entry
import com.aicove.core.plugin.BackupExtensionSource.Outcome
import com.aicove.core.plugin.BackupExtensionSource.Section
import com.aicove.core.plugin.BackupExtensionSource.Summary
import com.aicove.core.util.FileLogger
import com.aicove.feature.plugin.data.PluginBackupGrant
import com.aicove.feature.plugin.data.PluginBackupPermissionRepository
import com.aicove.feature.plugin.domain.InstalledPlugin
import com.aicove.feature.plugin.domain.PluginHook
import com.aicove.feature.plugin.domain.PluginHookEvents
import com.aicove.feature.plugin.domain.PluginHookModes
import com.aicove.feature.plugin.domain.PluginHostPaths
import com.aicove.feature.plugin.domain.PluginManager
import com.aicove.feature.plugin.domain.PluginManifestParser
import com.aicove.feature.plugin.domain.PluginUiSlots
import com.aicove.feature.workspace.domain.FileAccessProvider
import com.aicove.feature.workspace.domain.PathHomeResolver
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 插件只接触自己的 staging；授权、路径身份与文件大小在实际读写和派发前再次校验。 */
@Singleton
class PluginBackupArchiveService @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtime: PluginRuntimeManager,
    private val permissionRepository: PluginBackupPermissionRepository,
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver
) : BackupExtensionSource {
    private data class Staging(
        val directory: String, val grant: PluginBackupGrant, val version: String,
        val installRevision: String?, val archiveSha256: String, val installedAt: Long
    )
    private val exports = mutableMapOf<String, Staging>()
    private val imports = mutableMapOf<String, Staging>()

    override fun entries(languageTag: String): Flow<List<Entry>> = combine(
        pluginManager.plugins, permissionRepository.grants
    ) { plugins, grants ->
        plugins.filter { it.isActive && it.content.backup != null && it.id in grants }
            .map { plugin ->
                val title = plugin.content.uiEntries.firstOrNull { it.slot == PluginUiSlots.BACKUP_DATA }?.title
                    ?: plugin.manifest!!.name
                Entry(plugin.id, title.resolve(languageTag))
            }
    }.onStart { pluginManager.ensureLoaded() }

    override suspend fun prepareExport(pluginIds: Set<String>): List<Section> = withContext(Dispatchers.IO) {
        val sections = mutableListOf<Section>()
        try {
            pluginManager.ensureLoaded()
            for (id in pluginIds) {
                val plugin = eligible(id) ?: continue
                val hooks = hooks(plugin, PluginHookEvents.BACKUP_EXPORT_BEFORE)
                if (hooks.isEmpty()) continue
                val stage = newStaging(plugin, EXPORT_DIR) ?: continue
                exports[id] = stage
                sections += Section(id, stage.directory)
                resetDirectory(stage.directory)
                val payload = buildJsonObject { put("dir", "${PluginHostPaths.DATA_SCHEME}$EXPORT_DIR") }
                hooks.forEach {
                    requireCurrent(id, stage)
                    invoke(plugin, it, payload)
                }
            }
            sections
        } catch (e: Throwable) {
            cleanupExport(sections)
            throw e
        }
    }

    override suspend fun listExportFiles(section: Section): List<String> {
        requireExport(section)
        val files = mutableListOf<String>()
        fun walk(directory: String, prefix: String, depth: Int) {
            for (entry in fileAccess.listFiles(directory)) {
                val relative = prefix + entry.name
                require(isValidSectionPath(relative)) { "Invalid plugin staging path" }
                val path = checkedPath(section.directory, relative)
                if (entry.isDirectory) {
                    require(depth < BackupExtensionSource.MAX_DEPTH) { "Plugin staging depth exceeded" }
                    walk(path, "$relative/", depth + 1)
                } else {
                    require(fileAccess.isFile(path)) { "Plugin staging is not a regular file" }
                    require(files.size < BackupExtensionSource.MAX_FILES) { "Plugin staging file limit exceeded" }
                    files += relative
                }
            }
        }
        walk(section.directory, "", 1)
        return files
    }

    override suspend fun exportFileSize(section: Section, relativePath: String): Long {
        requireExport(section)
        return fileAccess.fileSize(checkedPath(section.directory, relativePath)).also {
            require(it in 0..BackupExtensionSource.MAX_FILE_BYTES) { "Plugin file size exceeded" }
        }
    }

    override suspend fun copyExportFile(section: Section, relativePath: String, output: OutputStream, size: Long) {
        requireExport(section)
        require(size in 0..BackupExtensionSource.MAX_FILE_BYTES)
        val path = checkedPath(section.directory, relativePath)
        require(fileAccess.fileSize(path) == size) { "Plugin staging changed" }
        val identity = fileAccess.resolvePath(path)
        val local = fileAccess.copyToLocal(path)
        val temporary = local.canonicalPath != identity
        try {
            require(local.isFile && local.length() == size) { "Plugin staging changed" }
            FileInputStream(local).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var left = size
                while (left > 0) {
                    val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    check(count > 0) { "Plugin staging truncated" }
                    output.write(buffer, 0, count)
                    left -= count
                }
                check(input.read() == -1) { "Plugin staging grew" }
            }
            requireExport(section)
        } finally {
            if (temporary) check(local.delete() || !local.exists()) { "Plugin export copy cleanup failed" }
        }
    }

    override suspend fun importTarget(pluginId: String): String? = withContext(Dispatchers.IO) {
        val plugin = eligible(pluginId) ?: return@withContext null
        if (hooks(plugin, PluginHookEvents.BACKUP_IMPORT_FINISHED).isEmpty()) return@withContext null
        val stage = newStaging(plugin, IMPORT_DIR) ?: return@withContext null
        imports[pluginId] = stage
        try {
            resetDirectory(stage.directory)
            stage.directory
        } catch (e: Throwable) {
            cleanupImport(setOf(pluginId))
            throw e
        }
    }

    override suspend fun writeImportFile(directory: String, relativePath: String, input: InputStream, size: Long) {
        val (id, stage) = imports.entries.firstOrNull { it.value.directory == directory }
            ?.let { it.key to it.value } ?: error("Unknown plugin import staging")
        requireCurrent(id, stage)
        require(size in 0..BackupExtensionSource.MAX_FILE_BYTES)
        val path = checkedPath(directory, relativePath)
        // TarArchiveInputStream 必须保留给下一条；后端不得关闭整个归档流。
        val source = object : FilterInputStream(input) {
            override fun close() = Unit
        }
        check(fileAccess.writeStream(path, source, overwrite = false) == size) { "Plugin import size mismatch" }
        checkedPath(directory, relativePath)
        requireCurrent(id, stage)
    }

    override suspend fun finishImport(pluginIds: Set<String>, summary: Summary): Outcome = withContext(Dispatchers.IO) {
        val completed = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        for (id in pluginIds) {
            val stage = imports[id]
            try {
                val plugin = eligible(id)
                if (stage == null || plugin == null || !isCurrent(id, stage)) {
                    skipped += id
                    continue
                }
                val payload = buildJsonObject {
                    put("dir", "${PluginHostPaths.DATA_SCHEME}$IMPORT_DIR")
                    put("stats", buildJsonObject {
                        put("chatSessions", summary.chatSessions)
                        put("workspaceFiles", summary.workspaceFiles)
                    })
                }
                val hooks = hooks(plugin, PluginHookEvents.BACKUP_IMPORT_FINISHED)
                if (hooks.isEmpty()) skipped += id else {
                    hooks.forEach {
                        requireCurrent(id, stage)
                        invoke(plugin, it, payload)
                    }
                    completed += id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "Plugin import failed: $id", e)
                failed += id
            }
        }
        Outcome(completed, skipped, failed)
    }

    override fun cleanupExport(sections: List<Section>) {
        cleanup(exports, sections.map { it.pluginId }.toSet())
    }

    override fun cleanupImport(pluginIds: Set<String>) {
        cleanup(imports, pluginIds)
    }

    private fun cleanup(stages: MutableMap<String, Staging>, ids: Set<String>) {
        var failure: Exception? = null
        for (id in ids) {
            val stage = stages[id] ?: continue
            try {
                checkedDirectory(stage.directory)
                fileAccess.deleteRecursively(stage.directory)
                stages.remove(id)
            } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun eligible(id: String): InstalledPlugin? {
        if (!PluginManifestParser.isValidPluginId(id)) return null
        pluginManager.ensureLoaded()
        return pluginManager.plugins.value.firstOrNull {
            it.id == id && it.isActive && it.content.backup != null && permissionRepository.isGranted(id)
        }
    }

    private suspend fun newStaging(plugin: InstalledPlugin, name: String): Staging? {
        val grant = permissionRepository.grants.first()[plugin.id] ?: return null
        return Staging(PluginHostPaths.dataRoot(homeResolver, plugin.id).trimEnd('/') + "/$name", grant, plugin.record.version,
            plugin.record.installRevision, plugin.record.archiveSha256, plugin.record.installedAt)
    }

    private suspend fun isCurrent(id: String, stage: Staging): Boolean {
        val record = eligible(id)?.record ?: return false
        return permissionRepository.grants.first()[id] == stage.grant && record.version == stage.version &&
            record.installRevision == stage.installRevision && record.archiveSha256 == stage.archiveSha256 &&
            record.installedAt == stage.installedAt
    }

    private suspend fun requireCurrent(id: String, stage: Staging) {
        check(isCurrent(id, stage)) { "Plugin backup permission revoked or plugin changed" }
    }

    private suspend fun requireExport(section: Section) {
        val stage = exports[section.pluginId] ?: error("Unknown plugin export staging")
        check(stage.directory == section.directory)
        requireCurrent(section.pluginId, stage)
        checkedDirectory(section.directory)
    }

    private fun resetDirectory(directory: String) {
        checkedDirectory(directory)
        fileAccess.deleteRecursively(directory)
        fileAccess.mkdirs(directory)
        checkedDirectory(directory)
    }

    private fun checkedDirectory(directory: String) {
        val base = homeResolver.aicodeRoot().trimEnd('/')
        val relative = directory.removePrefix("$base/")
        require(relative != directory && relative.startsWith("plugins/data/"))
        checkedPath(base, relative)
    }

    private fun checkedPath(directory: String, relative: String): String {
        require(isValidSectionPath(relative)) { "Invalid plugin section path" }
        val root = fileAccess.resolvePath(directory).trimEnd('/')
        var path = directory.trimEnd('/')
        var identity = root
        relative.split('/').forEach { part ->
            path += "/$part"
            identity += "/$part"
            check(fileAccess.resolvePath(path, false).trimEnd('/') == identity &&
                fileAccess.resolvePath(path).trimEnd('/') == identity) { "Plugin section contains a symbolic link" }
        }
        return path
    }

    override fun isValidSectionPath(path: String): Boolean =
        path.length <= PluginHostPaths.MAX_PATH_LENGTH && path.split('/').size <= BackupExtensionSource.MAX_DEPTH &&
            PluginManifestParser.isSafeRelativePath(path)

    private fun hooks(plugin: InstalledPlugin, event: String): List<PluginHook> =
        plugin.content.hooks.filter { it.event == event && it.mode == PluginHookModes.OBSERVE }

    private suspend fun invoke(plugin: InstalledPlugin, hook: PluginHook, payload: JsonObject) {
        val result = runtime.invoke(
            pluginId = plugin.id, version = plugin.record.version, runtime = hook.runtime,
            handler = hook.handler, args = payload, method = "hook.invoke", timeoutMs = HOOK_TIMEOUT_MS
        )
        val logs = (result as? JsonObject)?.get("logs") as? JsonArray ?: return
        logs.take(MAX_LOG_LINES).forEach { item ->
            val text = (item as? JsonPrimitive)?.contentOrNull?.take(MAX_LOG_CHARS) ?: return@forEach
            FileLogger.i(TAG, "[${plugin.id}/${hook.localId}] $text")
        }
    }

    private companion object {
        const val TAG = "PluginBackup"
        const val EXPORT_DIR = "backup-export"
        const val IMPORT_DIR = "backup-import"
        const val HOOK_TIMEOUT_MS = 60_000L
        const val MAX_LOG_LINES = 20
        const val MAX_LOG_CHARS = 500
    }
}
