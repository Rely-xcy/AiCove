package com.aicove.feature.backup.data

import com.aicove.core.plugin.BackupExtensionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

internal class BackupPluginSections(private val source: BackupExtensionSource) {
    private val mutex = Mutex()

    suspend fun export(tar: TarArchiveOutputStream, selected: Set<String>): BackupExtensionSource.Outcome = mutex.withLock {
        if (selected.isEmpty()) return@withLock BackupExtensionSource.Outcome()
        val sections = source.prepareExport(selected)
        val completed = mutableSetOf<String>()
        var files = 0
        var totalBytes = 0L
        try {
            for (section in sections) {
                for (relative in source.listExportFiles(section)) {
                    check(source.isValidSectionPath(relative)) { "Invalid plugin backup path" }
                    check(++files <= BackupExtensionSource.MAX_FILES) { "Plugin backup file limit exceeded" }
                    val size = source.exportFileSize(section, relative)
                    check(size in 0..BackupExtensionSource.MAX_FILE_BYTES) { "Plugin backup file too large" }
                    check(size <= BackupExtensionSource.MAX_TOTAL_BYTES - totalBytes) { "Plugin backup exceeds size limit" }
                    totalBytes += size
                    tar.putArchiveEntry(TarArchiveEntry("plugins/${section.pluginId}/$relative").apply { this.size = size })
                    source.copyExportFile(section, relative, tar, size)
                    tar.closeArchiveEntry()
                }
                completed += section.pluginId
            }
            BackupExtensionSource.Outcome(completed = completed, skipped = selected - completed)
        } finally {
            source.cleanupExport(sections)
        }
    }

    suspend fun importSession(): ImportSession {
        mutex.lock()
        return ImportSession(source) { mutex.unlock() }
    }

    internal class ImportSession(private val source: BackupExtensionSource, private val release: () -> Unit) {
        private val targets = mutableMapOf<String, String?>()
        private val failed = mutableSetOf<String>()
        private val seenPaths = mutableSetOf<String>()
        private var files = 0
        private var bytes = 0L

        suspend fun accept(tar: TarArchiveInputStream, entry: TarArchiveEntry) {
            check(++files <= BackupExtensionSource.MAX_FILES) { "Plugin backup file limit exceeded" }
            val path = entry.name.removePrefix("plugins/")
            val id = path.substringBefore('/')
            val relative = path.substringAfter('/', "")
            val validId = source.isValidSectionPath(id)
            if (!validId || !source.isValidSectionPath(relative) || !entry.isFile || !seenPaths.add(path)) {
                failed += if (validId) id else "[invalid-section]"
                return
            }
            check(entry.size in 0..BackupExtensionSource.MAX_FILE_BYTES && entry.size <= BackupExtensionSource.MAX_TOTAL_BYTES - bytes) {
                "Plugin backup size limit exceeded"
            }
            bytes += entry.size
            if (id in failed) return
            try {
                if (!targets.containsKey(id)) targets[id] = source.importTarget(id)
                val directory = targets[id] ?: return
                source.writeImportFile(directory, relative, tar, entry.size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed += id
            }
        }

        suspend fun finish(summary: BackupExtensionSource.Summary): BackupExtensionSource.Outcome {
            val available = targets.filterValues { it != null }.keys - failed
            val restored = if (available.isEmpty()) BackupExtensionSource.Outcome() else source.finishImport(available, summary)
            return BackupExtensionSource.Outcome(
                completed = restored.completed,
                skipped = targets.filterValues { it == null }.keys + restored.skipped,
                failed = failed + restored.failed
            )
        }

        fun close() {
            try { source.cleanupImport(targets.keys) } finally { release() }
        }
    }
}
