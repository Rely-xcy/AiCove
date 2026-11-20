package com.aicove.feature.settings.data.remote

import android.content.Context
import android.os.Build
import com.aicove.R
import com.aicove.feature.settings.data.repository.UpdateChannel
import com.aicove.feature.settings.presentation.component.compareVersions
import com.aicove.feature.settings.presentation.component.parseVersionTag
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Release 里的一个更新包资产。 */
data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
    /**
     * 安装包摘要（裸十六进制，无 `sha256:` 前缀）。GitHub Release 的 `digest` 与自建接口的
     * `sha256` 都会带来；缺失时不阻断下载，只跳过摘要校验（签名校验才是安全锚，见 [ApkVerifier]）。
     */
    val sha256: String?
)

/** 单个版本的更新日志与更新包。 */
data class VersionUpdate(
    val tag: String,
    val changelog: String,
    val asset: UpdateAsset? = null
)

/** 拉取到的更新信息：最新版本号 + 从当前版本到最新的更新日志。 */
data class UpdateInfo(
    val latestTag: String,
    val changelog: String,
    val updates: List<VersionUpdate>,
    /** fork 专属：更新源给的 APK 绝对直链（弹窗据此走应用内下载）。 */
    val downloadUrl: String? = null
) {
    /** 最新版本的更新包（应用内下载用）；该 Release 没挂 APK 资产时为 null。 */
    val latestAsset: UpdateAsset? get() = updates.firstOrNull()?.asset
}

/** 检查更新结果。 */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class NewVersion(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

@Singleton
class UpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backup: ForkUpdateCheckService
) {
    suspend fun checkForUpdate(
        currentVersion: String,
        localVersionCode: Int,
        channel: UpdateChannel
    ): UpdateCheckResult = checkUpdateSources(
        primary = { checkGitHub(currentVersion, channel) },
        backup = { backup.checkForUpdate(localVersionCode, channel) }
    )

    private suspend fun checkGitHub(currentVersion: String, channel: UpdateChannel): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = okhttp3.Request.Builder()
                    .url("$RELEASES_API?per_page=100")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                SHARED_CLIENT.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use UpdateCheckResult.Error("HTTP ${response.code}")
                    val releases = JsonParser.parseString(response.body?.string().orEmpty()).asJsonArray
                    val info = parseGitHubUpdates(
                        releases = releases,
                        currentVersion = currentVersion,
                        channel = channel,
                        abiToken = preferredAbiToken(Build.SUPPORTED_ABIS.toList()),
                        noChangelog = context.getString(R.string.about_no_changelog),
                        includeInstallAsset = context.packageName == RELEASE_APPLICATION_ID
                    )
                    if (info == null) UpdateCheckResult.UpToDate else UpdateCheckResult.NewVersion(info)
                }
            }.getOrElse {
                if (it is CancellationException) throw it
                UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error))
            }
        }

    private companion object {
        const val RELEASES_API = "https://api.github.com/repos/Rely-xcy/AiCove/releases"
        // GitHub Releases 只有正式包和同包名 RC；独立包名的 Beta 产物在 Actions。
        const val RELEASE_APPLICATION_ID = "Rely.aicove"
        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aicove.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}

internal suspend fun checkUpdateSources(
    primary: suspend () -> UpdateCheckResult,
    backup: suspend () -> UpdateCheckResult
): UpdateCheckResult {
    val result = primary()
    currentCoroutineContext().ensureActive()
    return if (result is UpdateCheckResult.Error) backup() else result
}

internal fun parseGitHubUpdates(
    releases: JsonArray,
    currentVersion: String,
    channel: UpdateChannel,
    abiToken: String?,
    noChangelog: String,
    includeInstallAsset: Boolean = true
): UpdateInfo? {
    require(GITHUB_VERSION.matches(currentVersion.removePrefix("v")))
    val updates = releases.mapNotNull { element ->
        val release = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        if (release.get("draft")?.asBoolean == true) return@mapNotNull null
        if (channel == UpdateChannel.STABLE && release.get("prerelease")?.asBoolean == true) {
            return@mapNotNull null
        }
        val tag = release.get("tag_name")?.asString ?: return@mapNotNull null
        val version = parseVersionTag(tag)?.takeIf { GITHUB_VERSION.matches(it) } ?: return@mapNotNull null
        if (channel == UpdateChannel.STABLE && version.substringBefore('+').contains('-')) return@mapNotNull null
        if (compareVersions(version, currentVersion.removePrefix("v")) <= 0) return@mapNotNull null
        val assets = release.getAsJsonArray("assets")?.mapNotNull asset@{ item ->
            val asset = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@asset null
            val name = asset.get("name")?.asString ?: return@asset null
            val url = asset.get("browser_download_url")?.asString ?: return@asset null
            UpdateAsset(
                name = name,
                url = url,
                size = asset.get("size")?.asLong ?: 0L,
                sha256 = asset.get("digest")?.takeIf { !it.isJsonNull }?.asString
                    ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            )
        }.orEmpty()
        VersionUpdate(
            tag = tag,
            changelog = release.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                .lines()
                .joinToString("\n") { it.trimEnd().replace("**", "").replace(Regex("^#{1,6}\\s+"), "") }
                .trim()
                .ifBlank { noChangelog },
            asset = if (includeInstallAsset) selectUpdateAsset(assets, tag, abiToken) else null
        )
    }.distinctBy { it.tag }
        .sortedWith { a, b -> compareVersions(b.tag.removePrefix("v"), a.tag.removePrefix("v")) }
    return updates.firstOrNull()?.let { latest ->
        UpdateInfo(
            latestTag = latest.tag,
            changelog = updates.joinToString("\n\n") { "${it.tag}\n${it.changelog}" },
            updates = updates
        )
    }
}

private val GITHUB_VERSION = Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")

/**
 * 设备主 ABI 对应的资产名架构段：按 [supportedAbis] 的优先顺序取第一个支持的架构
 * （`arm64-v8a` → `arm64`，`x86_64` → `x86_64`）。都不支持时返回 null，调用方退回 universal 包。
 */
internal fun preferredAbiToken(supportedAbis: List<String>): String? =
    supportedAbis.firstNotNullOfOrNull { abi ->
        when (abi.lowercase()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "x86_64"
            else -> null
        }
    }

/**
 * 从 Release 资产里挑出更新包。
 *
 * 资产命名由 CI 决定（`aicove-<架构>-<版本>.apk`，见 `.github/workflows/android-release.yml`）。
 * 同一 Release 里还混着 `mapping.txt.gz`，所以只认 `.apk` 结尾；精确名没命中时依次退让为
 * 「含架构段」→ `universal`，都不命中返回 null（该版本没有可用安装包，只能去浏览器下载）。
 * 一律按 API 返回的资产名匹配，不自己拼下载 URL——CI 曾因段序拼错导致资产匹配不上。
 */
internal fun selectUpdateAsset(assets: List<UpdateAsset>, rawTag: String, abiToken: String?): UpdateAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    if (abiToken != null) {
        apks.firstOrNull {
            it.name.equals("aicove-$abiToken-$rawTag.apk", ignoreCase = true) ||
                it.name.equals("aicode-$abiToken-$rawTag.apk", ignoreCase = true)
        }?.let { return it }
        apks.firstOrNull {
            it.name.startsWith("aicove-$abiToken-", ignoreCase = true) ||
                it.name.startsWith("aicode-$abiToken-", ignoreCase = true)
        }?.let { return it }
    }
    return apks.firstOrNull {
        it.name.startsWith("aicove-universal-", ignoreCase = true) ||
            it.name.startsWith("aicode-universal-", ignoreCase = true)
    }
}
