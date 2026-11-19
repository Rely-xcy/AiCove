package com.aicove.feature.plugin.data

import com.aicove.feature.plugin.domain.ManifestParseResult
import com.aicove.feature.plugin.domain.PluginManifestParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

internal class PluginPublishPreparationException(val code: String, detail: String = code) : IllegalArgumentException(detail)

@Singleton
class PluginPublishPrep @Inject constructor() {
    fun readMeta(packageFile: File, languageTag: String = "zh-CN"): Result<PluginPublishMeta> = runCatching {
        if (!packageFile.isFile || packageFile.length() == 0L) throw PluginPublishPreparationException("PACKAGE_UNREADABLE")
        val manifestText = ZipFile(packageFile).use { zip ->
            val manifests = zip.entries().asSequence().filter {
                !it.isDirectory && it.name.substringAfterLast('/') == MANIFEST_FILE
            }.toList()
            if (manifests.isEmpty()) throw PluginPublishPreparationException("MANIFEST_MISSING")
            if (manifests.size != 1) throw PluginPublishPreparationException("MULTIPLE_MANIFESTS")
            val entry = manifests.single()
            if (entry.size > MAX_MANIFEST_BYTES) throw PluginPublishPreparationException("MANIFEST_TOO_LARGE")
            zip.getInputStream(entry).use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_MANIFEST_BYTES) throw PluginPublishPreparationException("MANIFEST_TOO_LARGE")
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray().decodeToString()
            }
        }
        when (val parsed = PluginManifestParser.parse(manifestText)) {
            is ManifestParseResult.Success -> parsed.manifest.let { manifest ->
                PluginPublishMeta(
                    id = manifest.id,
                    name = manifest.name.resolve(languageTag),
                    version = manifest.version.toString(),
                    description = manifest.description.resolve(languageTag),
                    author = manifest.publisher,
                    license = manifest.license.ifBlank { "MIT" }
                )
            }
            is ManifestParseResult.Failure -> throw PluginPublishPreparationException(
                "MANIFEST_INVALID", parsed.errors.joinToString { it.code.name }
            )
        }
    }

    private companion object {
        const val MANIFEST_FILE = "plugin.json"
        const val MAX_MANIFEST_BYTES = 1024 * 1024
    }
}
