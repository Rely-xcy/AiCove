package com.aicove.feature.plugin.data.index

import com.aicove.feature.plugin.domain.LocalizedText
import com.aicove.feature.plugin.domain.PluginManifestParser
import com.aicove.feature.plugin.domain.SemVer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 索引静态校验；不依赖 Android，便于单元测试。 */
internal object PluginIndexParser {

    private val AUTHOR = Regex("^[\\p{L}][\\p{L}\\p{N}_-]{0,31}$")
    private val LANGUAGE_TAG = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$")

    fun parse(bytes: ByteArray): PluginIndexDocument {
        if (bytes.size > MAX_INDEX_BYTES) throw PluginIndexException("index.json 超过 2 MiB")
        val root = try {
            PluginIndexJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw PluginIndexException("index.json 必须是对象")
        } catch (e: Exception) {
            throw PluginIndexException("index.json 解析失败", e)
        }
        val schemaVersion = root["schema_version"] as? JsonPrimitive
        if (schemaVersion?.isString != false || schemaVersion.content != "1") {
            throw PluginIndexException("不支持的 schema_version=$schemaVersion")
        }
        val entries = root["plugins"] as? JsonArray
            ?: throw PluginIndexException("index.json 的 plugins 必须是数组")
        if (entries.size > MAX_PLUGINS) throw PluginIndexException("插件数量超过 $MAX_PLUGINS")
        val ids = HashSet<String>()
        val plugins = mutableListOf<PluginIndexPlugin>()
        var rejectedPluginCount = 0
        entries.forEach { entry ->
            val id = ((entry as? JsonObject)?.get("id") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            if (id != null && !ids.add(id)) throw PluginIndexException("插件 ID 重复: $id")
            val plugin = try {
                PluginIndexJson.decodeFromString<PluginIndexPlugin>(entry.toString()).also(::validatePlugin)
            } catch (_: IllegalArgumentException) {
                rejectedPluginCount++
                return@forEach
            } catch (_: PluginIndexException) {
                rejectedPluginCount++
                return@forEach
            }
            plugins += plugin
        }
        if (plugins.isEmpty() && rejectedPluginCount > 0) {
            throw PluginIndexException("index.json 没有有效插件，已拒绝 $rejectedPluginCount 项")
        }
        return PluginIndexDocument(1, plugins, rejectedPluginCount)
    }

    private fun validatePlugin(plugin: PluginIndexPlugin) {
        if (!PluginManifestParser.isValidPluginId(plugin.id)) {
            throw PluginIndexException("非法插件 ID: ${plugin.id}")
        }
        validateLocalized(plugin.id, "name", plugin.name, MAX_NAME, allowNewlines = false)
        validateLocalized(plugin.id, "description", plugin.description, MAX_DESCRIPTION, allowNewlines = true)
        if (!AUTHOR.matches(plugin.author)) throw PluginIndexException("${plugin.id} 的 author 非法")
        val repository = runCatching { java.net.URI(plugin.repository) }.getOrNull()
        if (repository?.scheme != "https" || repository.host != "github.com") {
            throw PluginIndexException("${plugin.id} 的 repository 不是 GitHub 地址")
        }
        plugin.version?.let { version ->
            if (version.startsWith("v") || SemVer.parse(version) == null) {
                throw PluginIndexException("${plugin.id} 的 version 非法")
            }
        }
        plugin.packageUrl?.let { url ->
            val version = plugin.version ?: throw PluginIndexException("${plugin.id} 的 package_url 缺少 version")
            if (version != version.trim()) throw PluginIndexException("${plugin.id} 的 version 非法")
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                uri.rawFragment != null || (uri.port != -1 && uri.port !in 1..65535)
            ) {
                throw PluginIndexException("${plugin.id} 的 package_url 必须是有效的 https 地址")
            }
        }
        plugin.packageSha256?.let { digest ->
            if (!Regex("^[0-9a-fA-F]{64}$").matches(digest)) {
                throw PluginIndexException("${plugin.id} 的 package_sha256 非法")
            }
        }
        plugin.minAppVersion?.let {
            if (SemVer.parse(it) == null) throw PluginIndexException("${plugin.id} 的 min_app_version 非法")
        }
        if (plugin.tags.any { it !in PluginTags.ALL }) {
            throw PluginIndexException("${plugin.id} 的 tags 含未知值")
        }
        if (plugin.tags.size > 4 || plugin.tags.distinct().size != plugin.tags.size) {
            throw PluginIndexException("${plugin.id} 的 tags 数量或重复项非法")
        }
        plugin.logo?.let { requireHttps(it, "${plugin.id} logo") }
        plugin.homepage?.let { requireHttps(it, "${plugin.id} homepage") }
    }

    private fun validateLocalized(
        pluginId: String,
        field: String,
        text: LocalizedText,
        maxLength: Int,
        allowNewlines: Boolean
    ) {
        if (text.values.size > MAX_LOCALES) {
            throw PluginIndexException("$pluginId 的 $field 语言数量超过 $MAX_LOCALES")
        }
        text.values.forEach { (tag, value) ->
            if (tag != LocalizedText.DEFAULT_KEY && !LANGUAGE_TAG.matches(tag)) {
                throw PluginIndexException("$pluginId 的 $field 含非法语言标签: $tag")
            }
            if (value.isEmpty() || value.length > maxLength || hasControlChars(value, allowNewlines)) {
                throw PluginIndexException("$pluginId 的 $field 文本非法: $tag")
            }
        }
    }

    private fun hasControlChars(text: String, allowNewlines: Boolean) =
        text.any { it.isISOControl() && !(allowNewlines && it == '\n') }

    private fun requireHttps(url: String, field: String) {
        if (!url.startsWith("https://")) throw PluginIndexException("$field 必须是 https 地址")
    }

    private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
    private const val MAX_PLUGINS = 5000
    private const val MAX_NAME = 120
    private const val MAX_DESCRIPTION = 2000
    private const val MAX_LOCALES = 16
}
