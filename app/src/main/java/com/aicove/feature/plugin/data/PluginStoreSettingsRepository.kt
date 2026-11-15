package com.aicove.feature.plugin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicove.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pluginStoreSettingsDataStore by preferencesDataStore(
    name = "plugin_store_settings",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 插件商店的通用设置。
 *
 * 镜像加速是一组 URL 前缀，按顺序尝试；只作用于 GitHub 托管的索引与插件包。
 * 用户自定义的地址排在内置候选之前，内置候选始终保留——单个镜像挂掉时能自动换下一个，
 * 不用等用户手动改配置。
 */
@Singleton
class PluginStoreSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** 用户自定义的镜像地址（单行，留空表示只用内置候选）。 */
    val downloadMirror: Flow<String> = context.pluginStoreSettingsDataStore.data.map { it[KEY].orEmpty() }

    /**
     * 实际要尝试的镜像前缀列表：用户自定义的排最前，其后是内置候选。
     * 内置候选为空字符串（直连）时列表以它结尾，保证总有退路。
     */
    val mirrorCandidates: Flow<List<String>> = downloadMirror.map { custom ->
        val text = custom.trim()
        buildList {
            if (text.isNotEmpty()) add(text)
            addAll(BUILTIN_MIRRORS)
        }.distinct()
    }

    /** 只接受 http(s) 前缀；空白表示关闭加速，非法输入直接忽略。 */
    suspend fun setDownloadMirror(url: String) {
        val text = url.trim()
        if (text.isNotEmpty() && !isValidMirror(text)) return
        context.pluginStoreSettingsDataStore.edit { it[KEY] = text }
    }

    private fun isValidMirror(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && uri.host != null && uri.userInfo == null
    }

    companion object {
        private val KEY = stringPreferencesKey("download_mirror")

        /**
         * 内置镜像候选，按可用性从高到低排列；末尾的空串代表直连 GitHub 本身。
         * 这些都是公开的 GitHub 加速前缀，用法都是「前缀 + 原始 URL」。
         */
        val BUILTIN_MIRRORS: List<String> = listOf(
            "",
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/"
        )
    }
}
