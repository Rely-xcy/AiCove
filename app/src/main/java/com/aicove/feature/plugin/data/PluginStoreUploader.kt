package com.aicove.feature.plugin.data

import android.content.Context
import com.aicove.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PluginUploadResult(val ok: Boolean, val message: String, val detailUrl: String? = null)

data class PluginPublishMeta(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val license: String = "MIT"
)

interface PluginPublishBackend {
    val isConfigured: Boolean
    suspend fun submit(packageFile: File, meta: PluginPublishMeta): PluginUploadResult
}

@Singleton
class PluginStoreUploader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backend: PluginPublishBackend
) {
    fun isConfigured(): Boolean = backend.isConfigured

    suspend fun upload(packageFile: File, meta: PluginPublishMeta): PluginUploadResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext PluginUploadResult(false, context.getString(R.string.muse_plugin_publish_unconfigured))
        }
        if (!packageFile.isFile || packageFile.length() == 0L) {
            return@withContext PluginUploadResult(false, context.getString(R.string.muse_plugin_publish_unreadable))
        }
        if (packageFile.length() > MAX_PACKAGE_BYTES) {
            return@withContext PluginUploadResult(false, context.getString(R.string.muse_plugin_publish_too_large, MAX_PACKAGE_MB))
        }
        try {
            backend.submit(packageFile, meta)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PluginUploadResult(false, context.getString(R.string.muse_plugin_publish_failed))
        }
    }

    companion object {
        internal const val MAX_PACKAGE_MB = 20
        const val MAX_PACKAGE_BYTES = MAX_PACKAGE_MB * 1024L * 1024
    }
}
