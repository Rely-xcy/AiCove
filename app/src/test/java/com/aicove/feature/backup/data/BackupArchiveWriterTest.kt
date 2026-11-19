package com.aicove.feature.backup.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun jsonLinesKeepExactUtf8LengthsAndRemoveTemporaryFiles() = runBlocking {
        val file = temporary.newFile("jsonl.tmp")
        val bytes = ByteArrayOutputStream()
        val text = "{\"title\":\"中文\"}\n{\"title\":\"example\"}\n"
        val writer = BackupArchiveWriter(Json, { file })
        TarArchiveOutputStream(bytes).use { tar ->
            writer.writeJsonlFileEntry(tar, "messages.jsonl") { jsonl ->
                text.lineSequence().filter { it.isNotEmpty() }.forEach { jsonl.writeLine(it) }
            }
        }
        assertFalse(file.exists())
        TarArchiveInputStream(ByteArrayInputStream(bytes.toByteArray())).use { tar ->
            val entry = tar.nextTarEntry
            assertEquals("messages.jsonl", entry.name)
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), entry.size)
            assertEquals(text, String(tar.readBytes(), Charsets.UTF_8))
        }
    }
}
