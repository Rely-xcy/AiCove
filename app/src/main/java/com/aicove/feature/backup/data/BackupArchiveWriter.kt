package com.aicove.feature.backup.data

import com.aicove.core.plugin.BackupExtensionSource
import com.aicove.feature.backup.domain.BackupMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

internal class BackupArchiveWriter(private val json: Json, private val temporaryFile: () -> File) {
    data class Result(val workspaceFiles: Map<String, Int>, val plugins: BackupExtensionSource.Outcome)
    fun writeMetadataEntry(tar: TarArchiveOutputStream, metadata: BackupMetadata) {
        writeTarEntry(tar, "metadata.json", json.encodeToString(BackupMetadata.serializer(), metadata).toByteArray(Charsets.UTF_8))
    }

    fun writeTarEntry(tar: TarArchiveOutputStream, name: String, content: ByteArray) {
        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
        tar.write(content)
        tar.closeArchiveEntry()
    }

    // tar 头必须预先包含准确长度；暂存 JSONL 后再流式写入，避免把整表装入内存。
    suspend fun writeJsonlFileEntry(
        tar: TarArchiveOutputStream,
        entryName: String,
        block: suspend (JsonlWriter) -> Unit
    ) {
        val tmp = temporaryFile()
        try {
            FileOutputStream(tmp).use { output ->
                val writer = JsonlWriter(output)
                block(writer)
                writer.flush()
            }
            tar.putArchiveEntry(TarArchiveEntry(entryName).apply { size = tmp.length() })
            FileInputStream(tmp).use { it.copyTo(tar) }
            tar.closeArchiveEntry()
        } finally {
            tmp.delete()
        }
    }
}

internal class JsonlWriter(private val out: OutputStream) {
    private val buffer = ByteArrayOutputStream(64 * 1024)

    fun writeLine(line: String) {
        buffer.write(line.toByteArray(Charsets.UTF_8))
        buffer.write('\n'.code)
        if (buffer.size() >= 64 * 1024) flush()
    }

    fun flush() {
        buffer.writeTo(out)
        buffer.reset()
    }
}
