package com.aicove.core.plugin

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow

/** 备份只接收插件自有暂存区段；端口不暴露宿主归档、配置或凭据。 */
interface BackupExtensionSource {
    data class Entry(val pluginId: String, val title: String)
    data class Section(val pluginId: String, val directory: String)
    data class Summary(val chatSessions: Int, val workspaceFiles: Int)
    data class Outcome(
        val completed: Set<String> = emptySet(),
        val skipped: Set<String> = emptySet(),
        val failed: Set<String> = emptySet()
    )

    fun entries(languageTag: String): Flow<List<Entry>>
    suspend fun prepareExport(pluginIds: Set<String>): List<Section>
    suspend fun listExportFiles(section: Section): List<String>
    suspend fun exportFileSize(section: Section, relativePath: String): Long
    suspend fun copyExportFile(section: Section, relativePath: String, output: OutputStream, size: Long)
    suspend fun importTarget(pluginId: String): String?
    suspend fun writeImportFile(directory: String, relativePath: String, input: InputStream, size: Long)
    suspend fun finishImport(pluginIds: Set<String>, summary: Summary): Outcome
    fun cleanupExport(sections: List<Section>)
    fun cleanupImport(pluginIds: Set<String>)
    fun isValidSectionPath(path: String): Boolean

    companion object {
        const val MAX_FILES = 1_000
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_DEPTH = 8
    }
}
