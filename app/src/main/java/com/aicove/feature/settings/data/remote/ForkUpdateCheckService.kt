package com.aicove.feature.settings.data.remote

import android.content.Context
import com.aicove.R
import com.aicove.feature.settings.data.repository.UpdateChannel
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val FORK_UPDATE_SUPPORTS_CHANNEL = true

/** GitHub 检查失败时使用的自有更新源；判新仍完全遵循服务端 hasUpdate / direction。 */
@Singleton
class ForkUpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun checkForUpdate(
        localVersionCode: Int,
        channel: UpdateChannel
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder()
                .url("$ENDPOINT?channel=${channel.wireName}&code=$localVersionCode")
                .get()
                .build()
            SHARED_CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use UpdateCheckResult.Error("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val obj = runCatching { JsonParser.parseString(body).asJsonObject }
                    .getOrNull()
                    ?: return@use UpdateCheckResult.Error(context.getString(R.string.about_parse_version_failed))
                if (obj.get("ok")?.takeIf { !it.isJsonNull }?.asBoolean == false) {
                    return@use UpdateCheckResult.Error(context.getString(R.string.about_network_error))
                }
                if (!shouldReportUpdate(
                        released = obj.booleanOrFalse("released"),
                        hasUpdate = obj.booleanOrFalse("hasUpdate"),
                        direction = obj.stringOrEmpty("direction")
                    )
                ) {
                    return@use UpdateCheckResult.UpToDate
                }
                UpdateCheckResult.NewVersion(
                    parseForkUpdateInfo(obj, localVersionCode, channel, context.getString(R.string.about_no_changelog))
                )
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error))
        }
    }

    private companion object {
        const val ENDPOINT = "https://rely-xfer.de5.net/dl/update"
        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aicove.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}

/** 顶层始终是唯一下载目标；releases 只提供同通道的中间版本日志。 */
internal fun parseForkUpdateInfo(
    obj: JsonObject,
    localVersionCode: Int,
    channel: UpdateChannel,
    noChangelog: String
): UpdateInfo {
    val label = obj.releaseLabel()
    val note = obj.releaseNote().ifBlank { noChangelog }
    val url = obj.stringOrEmpty("url").ifBlank {
        obj.stringOrEmpty("path").takeIf { it.isNotBlank() }?.let { FORK_SITE_BASE + it + "?dl=1" }.orEmpty()
    }.takeIf { it.isNotBlank() }
    val asset = url?.let {
        UpdateAsset(
            name = obj.stringOrEmpty("name").ifBlank { "aicove-update.apk" },
            url = it,
            size = obj.longOrZero("size"),
            sha256 = obj.stringOrEmpty("sha256").takeIf { hash -> hash.length == 64 }
        )
    }
    val latestCode = obj.longOrZero("versionCode")
    val history = obj.get("releases")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { it.takeIf { release -> release.isJsonObject }?.asJsonObject }.orEmpty()
        .filter {
            it.stringOrEmpty("channel") == channel.wireName &&
                it.longOrZero("versionCode") > localVersionCode &&
                it.longOrZero("versionCode") < latestCode
        }
        .sortedByDescending { it.longOrZero("versionCode") }
        .distinctBy { it.longOrZero("versionCode") }
        .map { VersionUpdate(tag = it.releaseLabel(), changelog = it.releaseNote().ifBlank { noChangelog }) }
    val updates = listOf(VersionUpdate(tag = label, changelog = note, asset = asset)) + history
    val sizeText = obj.stringOrEmpty("sizeText")
    return UpdateInfo(
        latestTag = label,
        changelog = updates.mapIndexed { index, update ->
            val heading = if (index == 0 && sizeText.isNotBlank()) "${update.tag} ($sizeText)" else update.tag
            "$heading\n${update.changelog}"
        }.joinToString("\n\n"),
        updates = updates,
        downloadUrl = url
    )
}

private fun JsonObject.releaseLabel(): String = listOf(
    stringOrEmpty("versionName").ifBlank { stringOrEmpty("title") }
        .ifBlank { stringOrEmpty("name").trim().removeSuffix(".apk") },
    stringOrEmpty("date")
).filter { it.isNotBlank() }.joinToString(" · ")

private fun JsonObject.releaseNote(): String = stringOrEmpty("note").ifBlank { stringOrEmpty("noteShort") }

private fun JsonObject.stringOrEmpty(key: String): String =
    get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

private fun JsonObject.booleanOrFalse(key: String): Boolean =
    get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

private fun JsonObject.longOrZero(key: String): Long =
    get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L

private const val FORK_SITE_BASE = "https://rely-xfer.de5.net"
internal const val DIRECTION_DOWN = "down"

internal fun shouldReportUpdate(released: Boolean, hasUpdate: Boolean, direction: String): Boolean =
    released && hasUpdate && direction != DIRECTION_DOWN
