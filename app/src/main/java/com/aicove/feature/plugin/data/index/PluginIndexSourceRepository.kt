package com.aicove.feature.plugin.data.index

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicove.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val Context.pluginIndexDataStore by preferencesDataStore(
    name = "plugin_index_sources",
    corruptionHandler = preferencesCorruptionHandler
)

data class PluginIndexSource(
    val id: String,
    val name: String,
    val indexUrl: String,
    val official: Boolean,
    val lastFetchedAt: Long = 0
) {
    val rootUrl: String get() = indexUrl.substringBeforeLast('/') + "/"
}

object PluginIndexDefaults {
    const val ALL_SOURCES_ID = "all"

    val builtIns = listOf(
        PluginIndexSource(
            id = "aicove.official",
            name = "AiCove",
            indexUrl = "https://raw.githubusercontent.com/Rely-xcy/AiCove-Plugins-Store/main/index.json",
            official = true
        ),
        PluginIndexSource(
            id = "aicode.official",
            name = "Upstream",
            indexUrl = "https://raw.githubusercontent.com/jieapi/AiCode-Plugins-Store/main/index.json",
            official = false
        ),
        PluginIndexSource(
            id = "muse.store",
            name = "Muse",
            indexUrl = "https://raw.githubusercontent.com/xzyy03/Muse-Plugins-Store/main/index.json",
            official = false
        )
    )
}

data class PluginIndexSelection(val sources: List<PluginIndexSource>, val selectedSourceId: String)

@Serializable
private data class StoredSource(
    val id: String,
    val name: String,
    val indexUrl: String,
    val lastFetchedAt: Long = 0
)

@Serializable
private data class StoredSources(
    val sources: List<StoredSource> = emptyList(),
    val selectedSourceId: String = PluginIndexDefaults.ALL_SOURCES_ID
)

@Singleton
class PluginIndexSourceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val selection: Flow<PluginIndexSelection> = context.pluginIndexDataStore.data.map { preferences ->
        val stored = decode(preferences[KEY])
        val available = availableSources(stored)
        PluginIndexSelection(
            available,
            stored.selectedSourceId.takeIf { id ->
                id == PluginIndexDefaults.ALL_SOURCES_ID || available.any { it.id == id }
            } ?: PluginIndexDefaults.ALL_SOURCES_ID
        )
    }

    val sources: Flow<List<PluginIndexSource>> = selection.map { it.sources }

    val customIndexUrls: Flow<List<String>> = sources.map { list ->
        list.filterNot { source -> PluginIndexDefaults.builtIns.any { it.id == source.id } }.map { it.indexUrl }
    }

    suspend fun selectSource(id: String) {
        updateStored { stored ->
            require(id == PluginIndexDefaults.ALL_SOURCES_ID || availableSources(stored).any { it.id == id }) {
                "Unknown plugin source"
            }
            stored.copy(selectedSourceId = id)
        }
    }

    suspend fun setCustomSources(lines: List<String>): Result<Unit> = try {
        val urls = lines.filter { it.isNotBlank() }.map(::normalizeIndexUrl).distinct()
            .filterNot { url -> PluginIndexDefaults.builtIns.any { it.indexUrl == url } }
        updateStored { stored ->
            val existing = stored.sources.associateBy { it.indexUrl }
            val custom = urls.map { url ->
                existing[url] ?: StoredSource(sourceId(url), defaultName(url), url)
            }
            val builtIns = stored.sources.filter { row -> PluginIndexDefaults.builtIns.any { it.id == row.id } }
            val selected = stored.selectedSourceId.takeIf { id ->
                id == PluginIndexDefaults.ALL_SOURCES_ID ||
                    PluginIndexDefaults.builtIns.any { it.id == id } || custom.any { it.id == id }
            } ?: PluginIndexDefaults.ALL_SOURCES_ID
            stored.copy(sources = builtIns + custom, selectedSourceId = selected)
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun markFetched(id: String, fetchedAt: Long) {
        updateStored { stored ->
            val source = availableSources(stored).firstOrNull { it.id == id } ?: return@updateStored stored
            val updated = StoredSource(source.id, source.name, source.indexUrl, fetchedAt)
            stored.copy(sources = if (stored.sources.any { it.id == id }) {
                stored.sources.map { if (it.id == id) updated else it }
            } else stored.sources + updated)
        }
    }

    private suspend fun updateStored(block: (StoredSources) -> StoredSources) {
        context.pluginIndexDataStore.edit { preferences ->
            preferences[KEY] = PluginIndexJson.encodeToString(block(decode(preferences[KEY])))
        }
    }

    private fun availableSources(stored: StoredSources): List<PluginIndexSource> =
        PluginIndexDefaults.builtIns.map { source ->
            source.copy(lastFetchedAt = stored.sources.firstOrNull { it.id == source.id }?.lastFetchedAt ?: 0)
        } + stored.sources.filterNot { row ->
            PluginIndexDefaults.builtIns.any { it.id == row.id || it.indexUrl == row.indexUrl }
        }.map { PluginIndexSource(it.id, it.name, it.indexUrl, official = false, lastFetchedAt = it.lastFetchedAt) }

    private fun decode(raw: String?): StoredSources = raw?.let {
        runCatching { PluginIndexJson.decodeFromString<StoredSources>(it) }.getOrNull()
    } ?: StoredSources()

    companion object {
        private val KEY = stringPreferencesKey("sources_json")

        internal fun normalizeIndexUrl(raw: String): String {
            val text = raw.trim()
            val uri = URI(text)
            require(uri.scheme in setOf("https", "http") && uri.host != null) { "Invalid index URL" }
            require(uri.userInfo == null && uri.fragment == null) { "Invalid index URL" }
            if (uri.host.lowercase() !in setOf("github.com", "www.github.com")) return text
            require(uri.query == null) { "Use a repository or index file URL" }
            val parts = uri.rawPath.trim('/').split('/')
            require(parts.size >= 2 && parts.none { it.isBlank() || it == "." || it == ".." }) { "Invalid repository URL" }
            val base = "https://raw.githubusercontent.com/${parts[0]}/${parts[1].removeSuffix(".git")}"
            return when {
                parts.size == 2 -> "$base/main/index.json"
                parts.size >= 5 && parts[2] == "blob" -> "$base/${parts.drop(3).joinToString("/")}"
                parts.size == 4 && parts[2] == "tree" -> "$base/${parts[3]}/index.json"
                else -> throw IllegalArgumentException("Use a repository or index file URL")
            }
        }

        private fun defaultName(indexUrl: String): String {
            val uri = URI(indexUrl)
            val parts = uri.path.trim('/').split('/')
            return if (uri.host == "raw.githubusercontent.com" && parts.size >= 2) {
                "${parts[0]}/${parts[1]}"
            } else uri.host
        }

        private fun sourceId(indexUrl: String): String = MessageDigest.getInstance("SHA-256")
            .digest(indexUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
    }
}
