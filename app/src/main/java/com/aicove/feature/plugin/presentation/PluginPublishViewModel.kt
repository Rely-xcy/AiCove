package com.aicove.feature.plugin.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicove.R
import com.aicove.feature.plugin.data.PluginDevPackageBuilder
import com.aicove.feature.plugin.data.PluginPublishMeta
import com.aicove.feature.plugin.data.PluginPublishPrep
import com.aicove.feature.plugin.data.PluginPublishPreparationException
import com.aicove.feature.plugin.data.PluginStoreUploader
import com.aicove.feature.plugin.domain.PluginManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PluginPublishUiState(
    val busy: Boolean = false,
    val meta: PluginPublishMeta? = null,
    val status: String? = null,
    val submitted: Boolean = false
)

@HiltViewModel
class PluginPublishViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prep: PluginPublishPrep,
    private val uploader: PluginStoreUploader,
    private val manager: PluginManager
) : ViewModel() {
    private val _state = MutableStateFlow(PluginPublishUiState())
    val state = _state.asStateFlow()
    private var packageFile: File? = null
    val backendConfigured: Boolean get() = uploader.isConfigured()

    fun readPackage(uri: Uri) {
        if (_state.value.busy) return
        prepare { target ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > PluginStoreUploader.MAX_PACKAGE_BYTES) throw PluginPublishPreparationException("PACKAGE_TOO_LARGE")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw PluginPublishPreparationException("PACKAGE_UNREADABLE")
        }
    }

    fun prepareDevProject(project: PluginDevProject) {
        if (_state.value.busy) return
        prepare { target ->
            val source = File(manager.devDir, project.dirName)
            if (source.parentFile?.canonicalPath != manager.devDir.canonicalPath || !source.isDirectory) {
                throw PluginPublishPreparationException("PACKAGE_UNREADABLE")
            }
            PluginDevPackageBuilder.zip(source, target)
            if (target.length() > PluginStoreUploader.MAX_PACKAGE_BYTES) throw PluginPublishPreparationException("PACKAGE_TOO_LARGE")
        }
    }

    private fun prepare(copy: (File) -> Unit) {
        _state.value = PluginPublishUiState(busy = true, status = context.getString(R.string.muse_plugin_publish_reading))
        viewModelScope.launch(Dispatchers.IO) {
            packageFile?.delete()
            packageFile = null
            val target = File.createTempFile("plugin-publish-", ".zip", context.cacheDir)
            try {
                copy(target)
                val language = context.resources.configuration.locales[0].toLanguageTag()
                val meta = prep.readMeta(target, language).getOrThrow()
                packageFile = target
                _state.value = PluginPublishUiState(meta = meta)
            } catch (e: CancellationException) {
                target.delete()
                throw e
            } catch (e: Exception) {
                target.delete()
                val message = when ((e as? PluginPublishPreparationException)?.code) {
                    "PACKAGE_UNREADABLE" -> R.string.muse_plugin_publish_unreadable
                    "PACKAGE_TOO_LARGE" -> R.string.muse_plugin_publish_too_large
                    "MANIFEST_MISSING" -> R.string.muse_plugin_publish_manifest_missing
                    "MULTIPLE_MANIFESTS" -> R.string.muse_plugin_publish_bundle_unsupported
                    "MANIFEST_TOO_LARGE" -> R.string.muse_plugin_publish_manifest_too_large
                    else -> R.string.muse_plugin_publish_manifest_invalid
                }
                _state.value = PluginPublishUiState(status = if (message == R.string.muse_plugin_publish_too_large) {
                    context.getString(message, 20)
                } else context.getString(message))
            }
        }
    }

    fun submit() {
        val current = _state.value
        val meta = current.meta ?: return
        val file = packageFile ?: return
        if (current.busy) return
        _state.value = current.copy(busy = true)
        viewModelScope.launch {
            val result = uploader.upload(file, meta)
            _state.value = current.copy(status = result.message, submitted = result.ok)
        }
    }

    fun export(uri: Uri) {
        val current = _state.value
        val file = packageFile ?: return
        if (current.busy) return
        _state.value = current.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val status = try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { it.copyTo(output) }
                } ?: throw PluginPublishPreparationException("PACKAGE_UNREADABLE")
                context.getString(R.string.muse_plugin_publish_exported)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.getString(R.string.muse_plugin_publish_export_failed, e.message.orEmpty())
            }
            _state.value = current.copy(status = status)
        }
    }

    fun dismiss() {
        if (_state.value.busy) return
        packageFile?.delete()
        packageFile = null
        _state.value = PluginPublishUiState()
    }

    override fun onCleared() {
        packageFile?.delete()
        super.onCleared()
    }
}
