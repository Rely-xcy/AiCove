package com.aicove.feature.backup.data

import com.aicove.core.plugin.BackupExtensionSource
import com.aicove.core.plugin.BackupExtensionSource.Entry
import com.aicove.core.plugin.BackupExtensionSource.Outcome
import com.aicove.core.plugin.BackupExtensionSource.Section
import com.aicove.core.plugin.BackupExtensionSource.Summary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupPluginSectionsTest {
    private class Source : BackupExtensionSource {
        var prepareCalls = 0
        var cleanupCalls = 0
        var targetCalls = 0
        var failCopy = false
        var failWrite = false
        val imported = mutableMapOf<String, String>()
        override fun entries(languageTag: String): Flow<List<Entry>> = flowOf(listOf(Entry("demo.one", "Demo")))
        override suspend fun prepareExport(pluginIds: Set<String>): List<Section> {
            prepareCalls++
            return pluginIds.filter { it == "demo.one" }.map { Section(it, "/sandbox/export") }
        }
        override suspend fun listExportFiles(section: Section) = listOf("state.txt")
        override suspend fun exportFileSize(section: Section, relativePath: String) = 5L
        override suspend fun copyExportFile(section: Section, relativePath: String, output: OutputStream, size: Long) {
            if (failCopy) throw IOException("copy failed")
            output.write("hello".toByteArray())
        }
        override suspend fun importTarget(pluginId: String): String? {
            targetCalls++
            return if (pluginId == "demo.one") "/sandbox/import" else null
        }
        override suspend fun writeImportFile(directory: String, relativePath: String, input: InputStream, size: Long) {
            if (failWrite) throw IOException("write failed")
            imported[relativePath] = String(input.readBytes(), Charsets.UTF_8)
        }
        override suspend fun finishImport(pluginIds: Set<String>, summary: Summary) = Outcome(completed = pluginIds)
        override fun cleanupExport(sections: List<Section>) { cleanupCalls++ }
        override fun cleanupImport(pluginIds: Set<String>) { cleanupCalls++ }
        override fun isValidSectionPath(path: String) = path.isNotEmpty() && !path.startsWith('/') &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    @Test
    fun selectedPluginCreatesAnArchiveSectionAndCleansItsStaging() = runBlocking {
        val source = Source()
        val bytes = ByteArrayOutputStream()
        val outcome = TarArchiveOutputStream(bytes).use { tar ->
            BackupPluginSections(source).export(tar, setOf("demo.one", "demo.unavailable"))
        }
        assertEquals(setOf("demo.one"), outcome.completed)
        assertEquals(setOf("demo.unavailable"), outcome.skipped)
        assertEquals(1, source.cleanupCalls)
        TarArchiveInputStream(ByteArrayInputStream(bytes.toByteArray())).use { tar ->
            assertEquals("plugins/demo.one/state.txt", tar.nextTarEntry.name)
            assertEquals("hello", String(tar.readBytes(), Charsets.UTF_8))
        }
    }

    @Test
    fun noSelectionDoesNotInvokePluginHooks() = runBlocking {
        val source = Source()
        TarArchiveOutputStream(ByteArrayOutputStream()).use { tar ->
            assertEquals(Outcome(), BackupPluginSections(source).export(tar, emptySet()))
        }
        assertEquals(0, source.prepareCalls)
    }

    @Test
    fun restorationInvokesTheSelectedPluginAfterReadingItsFiles() = runBlocking {
        val source = Source()
        val session = BackupPluginSections(source).importSession()
        try {
            TarArchiveInputStream(ByteArrayInputStream(archive("plugins/demo.one/state.txt"))).use { tar ->
                session.accept(tar, tar.nextTarEntry)
            }
            assertEquals(setOf("demo.one"), session.finish(Summary(2, 3)).completed)
            assertEquals("hello", source.imported["state.txt"])
        } finally { session.close() }
        assertEquals(1, source.cleanupCalls)
    }

    @Test
    fun pathTraversalNeverCreatesAnImportTarget() = runBlocking {
        val source = Source()
        val session = BackupPluginSections(source).importSession()
        try {
            TarArchiveInputStream(ByteArrayInputStream(archive("plugins/demo.one/../../escape"))).use { tar ->
                session.accept(tar, tar.nextTarEntry)
            }
            assertEquals(setOf("demo.one"), session.finish(Summary(0, 0)).failed)
            assertEquals(0, source.targetCalls)
            assertTrue(source.imported.isEmpty())
        } finally { session.close() }
    }

    @Test
    fun unavailablePluginIsSkippedAndWriteFailureIsNotSuccess() = runBlocking {
        val source = Source().apply { failWrite = true }
        val session = BackupPluginSections(source).importSession()
        try {
            for (id in listOf("demo.one", "demo.unavailable")) {
                TarArchiveInputStream(ByteArrayInputStream(archive("plugins/$id/state.txt"))).use { tar ->
                    session.accept(tar, tar.nextTarEntry)
                }
            }
            val outcome = session.finish(Summary(0, 0))
            assertEquals(setOf("demo.one"), outcome.failed)
            assertEquals(setOf("demo.unavailable"), outcome.skipped)
            assertTrue(outcome.completed.isEmpty())
        } finally { session.close() }
    }

    @Test
    fun duplicatePluginSectionIsReportedAsFailure() = runBlocking {
        val source = Source()
        val session = BackupPluginSections(source).importSession()
        try {
            repeat(2) {
                TarArchiveInputStream(ByteArrayInputStream(archive("plugins/demo.one/state.txt"))).use { tar ->
                    session.accept(tar, tar.nextTarEntry)
                }
            }
            val outcome = session.finish(Summary(0, 0))
            assertEquals(setOf("demo.one"), outcome.failed)
            assertTrue(outcome.completed.isEmpty())
        } finally { session.close() }
    }

    @Test
    fun exportFailureStillCleansStaging() = runBlocking {
        val source = Source().apply { failCopy = true }
        val tar = TarArchiveOutputStream(ByteArrayOutputStream())
        try {
            BackupPluginSections(source).export(tar, setOf("demo.one"))
            fail("Expected export failure")
        } catch (_: IOException) {
            assertEquals(1, source.cleanupCalls)
        } finally { runCatching { tar.close() } }
    }

    private fun archive(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry(name).apply { size = 5 })
            tar.write("hello".toByteArray())
            tar.closeArchiveEntry()
        }
        return output.toByteArray()
    }
}
