package com.aicove.feature.plugin.tool

import android.content.Context
import com.aicove.core.util.FileLogger
import com.aicove.core.util.appLanguageTag
import com.aicove.feature.agent.domain.tool.AgentTool
import com.aicove.feature.agent.domain.tool.ParameterType
import com.aicove.feature.agent.domain.tool.ToolCapability
import com.aicove.feature.agent.domain.tool.ToolParameter
import com.aicove.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicove.feature.agent.domain.tool.ToolResult
import com.aicove.feature.plugin.data.PluginBackupPermissionRepository
import com.aicove.feature.plugin.data.PluginOverlayPermissionRepository
import com.aicove.feature.plugin.data.PluginShizukuPermissionRepository
import com.aicove.feature.plugin.domain.InstalledPlugin
import com.aicove.feature.plugin.domain.ManifestParseResult
import com.aicove.feature.plugin.domain.PluginInstallResult
import com.aicove.feature.plugin.domain.PluginInstallService
import com.aicove.feature.plugin.domain.PluginManager
import com.aicove.feature.plugin.domain.PluginManifestParser
import com.aicove.feature.plugin.overlay.PluginOverlayManager
import com.aicove.feature.plugin.runtime.PluginHookDispatcher
import com.aicove.feature.plugin.runtime.PluginModelService
import com.aicove.feature.plugin.runtime.PluginProviderRegistrar
import com.aicove.feature.plugin.runtime.PluginRuntimeManager
import com.aicove.feature.plugin.runtime.PluginTerminalRunner
import com.aicove.feature.plugin.runtime.PluginTipManager
import com.aicove.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class PluginTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: PluginManager,
    private val installer: Provider<PluginInstallService>,
    private val providers: Provider<PluginProviderRegistrar>,
    private val runtime: Provider<PluginRuntimeManager>,
    private val terminal: Provider<PluginTerminalRunner>,
    private val models: Provider<PluginModelService>,
    private val overlays: Provider<PluginOverlayManager>,
    private val hooks: Provider<PluginHookDispatcher>,
    private val shizukuPermissions: Provider<PluginShizukuPermissionRepository>,
    private val overlayPermissions: Provider<PluginOverlayPermissionRepository>,
    private val backupPermissions: Provider<PluginBackupPermissionRepository>,
    private val tips: Provider<PluginTipManager>,
    private val files: Provider<FileAccessProvider>
) : AgentTool() {
    override val name = "plugin"
    override val description = "管理本机插件：list 列出插件与贡献；install 从开发目录或 ZIP 安装；reload 重装开发项目；enable/disable 启停；uninstall 卸载；logs 查看运行日志。开发目录为 ~/.aicove/plugin-dev。安装、重载、启停和卸载会改变 Agent 配置，需遵循当前模式与授权。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_AGENT_CONFIG)
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "操作类型", enum = listOf("list", "install", "reload", "enable", "disable", "uninstall", "logs")),
        "plugin_id" to ToolParameter("plugin_id", ParameterType.STRING, "插件 ID，reload/enable/disable/uninstall/logs 必填", false),
        "source" to ToolParameter("source", ParameterType.STRING, "install 来源：dev 或 zip", false, enum = listOf("dev", "zip")),
        "path" to ToolParameter("path", ParameterType.STRING, "dev 填开发目录名；zip 填当前执行环境的包路径", false),
        "lines" to ToolParameter("lines", ParameterType.INTEGER, "日志行数，默认200，上限2000", false)
    )

    override fun effectiveCapabilities(args: Map<String, JsonElement>) =
        if (args.string("action") in setOf("list", "logs")) setOf(ToolCapability.READ_AGENT_CONFIG) else capabilities

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        manager.ensureLoaded()
        try {
            when (val action = args.string("action")) {
                "list" -> ToolResult.Success(buildJsonObject {
                    put("devDir", "~/.aicove/plugin-dev")
                    put("plugins", JsonArray(manager.plugins.value.map { describe(it) }))
                })
                "install" -> install(args)
                "reload" -> reload(args.string("plugin_id"))
                "enable", "disable", "uninstall" -> changeState(action, args.string("plugin_id"))
                "logs" -> logs(args.string("plugin_id"), args.string("lines")?.toIntOrNull() ?: 200)
                else -> ToolResult.Error("Unknown plugin action: $action", "INVALID_ARGUMENT")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error(e.message ?: "Plugin operation failed", "PLUGIN_OPERATION_FAILED")
        }
    }

    private fun describe(plugin: InstalledPlugin): JsonObject = buildJsonObject {
        put("id", plugin.id)
        put("name", plugin.manifest?.name?.resolve(context.appLanguageTag()) ?: plugin.id)
        put("version", plugin.record.version)
        put("enabled", plugin.record.enabled)
        put("active", plugin.isActive)
        put("status", plugin.status.name)
        put("tools", JsonArray(plugin.content.tools.map { JsonPrimitive(it.localId) }))
        put("skills", JsonArray(plugin.content.skills.map { JsonPrimitive(it.localId) }))
        put("agents", JsonArray(plugin.content.agents.map { JsonPrimitive(it.localId) }))
        put("workspaceTools", JsonArray(plugin.content.workspaceTools.map { JsonPrimitive(it.localId) }))
        put("providers", JsonArray(plugin.content.providers.map { JsonPrimitive(it.localId) }))
        put("hasRuntime", plugin.content.runtime != null)
    }

    private suspend fun install(args: Map<String, JsonElement>): ToolResult {
        val path = args.string("path") ?: return ToolResult.Error("Missing path", "MISSING_ARGUMENT")
        return when (args.string("source") ?: "dev") {
            "dev" -> installed(installer.get().installDevProject(path))
            "zip" -> {
                val access = files.get()
                val identity = access.resolvePath(path)
                val archive = access.copyToLocal(path)
                val temporary = archive.canonicalPath != identity
                try { installed(installer.get().installArchive(archive)) }
                finally { if (temporary) archive.delete() }
            }
            else -> ToolResult.Error("Source must be dev or zip", "INVALID_ARGUMENT")
        }
    }

    private suspend fun reload(id: String?): ToolResult {
        if (id == null) return ToolResult.Error("Missing plugin_id", "MISSING_ARGUMENT")
        val root = manager.devDir.canonicalFile
        val directory = root.listFiles().orEmpty().firstOrNull { candidate ->
            if (!candidate.isDirectory || candidate.canonicalFile.parentFile != root) return@firstOrNull false
            val manifest = File(candidate, "plugin.json")
            if (!manifest.isFile || manifest.length() > 1024L * 1024) return@firstOrNull false
            val parsed = PluginManifestParser.parse(manifest.readText())
            parsed is ManifestParseResult.Success && parsed.manifest.id == id
        } ?: return ToolResult.Error("Development project not found: $id", "DEV_DIR_NOT_FOUND")
        return installed(installer.get().installDevProject(directory.name))
    }

    private suspend fun changeState(action: String, id: String?): ToolResult {
        if (id == null) return ToolResult.Error("Missing plugin_id", "MISSING_ARGUMENT")
        val plugin = manager.plugins.value.firstOrNull { it.id == id }
            ?: return ToolResult.Error("Plugin not found: $id", "PLUGIN_NOT_FOUND")
        when (action) {
            "enable" -> {
                if (!manager.setEnabled(id, true)) return ToolResult.Error("Could not enable plugin", "UPDATE_FAILED")
                providers.get().sync(id)
                hooks.get().dispatchPluginEnabled(id)
            }
            "disable" -> {
                if (plugin.isActive) hooks.get().dispatchPluginDisabled(id)
                if (!manager.setEnabled(id, false)) return ToolResult.Error("Could not disable plugin", "UPDATE_FAILED")
                release(id)
                providers.get().sync(id)
            }
            "uninstall" -> {
                if (plugin.isActive) hooks.get().dispatchPluginUninstalled(id)
                val removed = manager.uninstall(id)
                if (manager.plugins.value.any { it.id == id }) return ToolResult.Error("Could not uninstall plugin", "UPDATE_FAILED")
                release(id)
                providers.get().removeAll(id)
                shizukuPermissions.get().revoke(id)
                overlayPermissions.get().revoke(id)
                backupPermissions.get().revoke(id)
                if (!removed) return ToolResult.Error("Plugin removed; residual file cleanup failed", "CLEANUP_FAILED")
            }
        }
        return ToolResult.Success(buildJsonObject { put("id", id); put("action", action); put("completed", true) })
    }

    private fun release(id: String) {
        runtime.get().closePlugin(id)
        terminal.get().closePlugin(id)
        models.get().closePlugin(id)
        overlays.get().closePlugin(id)
        tips.get().dismissPlugin(id)
    }

    private fun logs(id: String?, lines: Int): ToolResult {
        if (id == null || manager.plugins.value.none { it.id == id }) return ToolResult.Error("Plugin not found", "PLUGIN_NOT_FOUND")
        val directory = FileLogger.logDirectory() ?: return ToolResult.Error("Log directory unavailable", "LOG_UNAVAILABLE")
        val result = ArrayDeque<String>()
        val limit = lines.coerceIn(1, 2000)
        val log = directory.listFiles { f -> f.isFile && f.name.startsWith("log-") }.orEmpty().maxByOrNull { it.name }
        log?.useLines { rows -> rows.filter { it.contains("[$id]") }.forEach { row ->
            if (result.size == limit) result.removeFirst()
            result.addLast(row.take(4000))
        } }
        return ToolResult.Success(buildJsonObject { put("id", id); put("lines", JsonArray(result.map { JsonPrimitive(it) })) })
    }

    private fun installed(result: PluginInstallResult): ToolResult = when (result) {
        is PluginInstallResult.Installed -> ToolResult.Success(buildJsonObject {
            put("installed", true)
            put("id", result.pluginId)
            put("note", "Tool registration follows the plugin state; start a new chat to refresh the system skill list.")
        })
        is PluginInstallResult.Failed -> ToolResult.Error(result.error.detail.ifBlank { result.error.code.name }, result.error.code.name)
    }

    private fun Map<String, JsonElement>.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
