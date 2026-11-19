package com.aicove.feature.plugin.presentation

import com.aicove.feature.plugin.data.index.PluginIndexPlugin
import com.aicove.feature.plugin.data.index.PluginIndexSource
import com.aicove.feature.plugin.data.index.PluginOfficialRepositories
import com.aicove.feature.plugin.domain.PluginManifest
import java.net.URI

internal fun storePackageMismatches(
    index: PluginIndexPlugin,
    manifest: PluginManifest,
    resolvedVersion: String,
    archiveSha256: String
): List<String> = listOfNotNull(
    "id".takeIf { manifest.id != index.id },
    "version".takeIf { manifest.version.toString() != resolvedVersion },
    "package_sha256".takeIf {
        index.packageSha256 != null && !index.packageSha256.equals(archiveSha256, ignoreCase = true)
    }
)

internal fun isVerifiedOfficialPackage(
    source: PluginIndexSource,
    index: PluginIndexPlugin,
    manifestRepository: String
): Boolean = source.official && PluginOfficialRepositories.isOfficial(index.repository) &&
    normalizedPluginRepository(index.repository)?.let { it == normalizedPluginRepository(manifestRepository) } == true

private fun normalizedPluginRepository(value: String): String? {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host == null || uri.userInfo != null || uri.query != null || uri.fragment != null) {
        return null
    }
    val path = uri.path.trimEnd('/').removeSuffix(".git")
    if (path.isBlank()) return null
    return "https://${uri.host.lowercase()}${if (uri.host.equals("github.com", true)) path.lowercase() else path}"
}
