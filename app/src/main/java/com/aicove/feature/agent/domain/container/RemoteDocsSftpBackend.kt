package com.aicove.feature.agent.domain.container

import java.security.MessageDigest
import java.util.EnumSet
import java.util.UUID
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException

internal class RemoteDocsSftpBackend(
    private val client: SFTPClient,
    home: String,
    private val checkActive: suspend () -> Unit,
    configDirectory: String = home.trimEnd('/') + "/" + com.aicove.core.branding.BrandPaths.CONFIG_DIR_NAME,
    private val agentWrite: suspend (String, ByteArray) -> Boolean = { _, _ -> false }
) : BuiltinDocsSync.Backend {
    private val root = configDirectory.trimEnd('/') + "/docs"

    init {
        require(root.startsWith('/') && root.drop(1).split('/').all {
            it.isNotEmpty() && it != "." && it != ".." && it.none { char -> char <= '\u001f' }
        }) { "Invalid SSH home path" }
    }

    override suspend fun type(path: String): BuiltinDocsSync.EntryType? =
        checkedPath(path)?.let { lstat(it) }?.let {
            when (it.type) {
                FileMode.Type.REGULAR -> BuiltinDocsSync.EntryType.FILE
                FileMode.Type.DIRECTORY -> BuiltinDocsSync.EntryType.DIRECTORY
                FileMode.Type.SYMLINK -> BuiltinDocsSync.EntryType.SYMLINK
                else -> BuiltinDocsSync.EntryType.OTHER
            }
        }

    override suspend fun readFile(path: String): ByteArray {
        val remote = checkNotNull(checkedPath(path))
        val attrs = checkNotNull(lstat(remote))
        check(attrs.type == FileMode.Type.REGULAR && attrs.size in 0..BuiltinDocsSync.MAX_STATE_BYTES.toLong()) {
            "Unsafe built-in docs state file"
        }
        checkActive()
        return client.open(remote, EnumSet.of(OpenMode.READ)).use { file ->
            val bytes = ByteArray(attrs.size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                checkActive()
                val count = file.read(offset.toLong(), bytes, offset, minOf(32 * 1024, bytes.size - offset))
                check(count > 0) { "Incomplete built-in docs state" }
                offset += count
            }
            checkActive()
            check(file.read(offset.toLong(), ByteArray(1), 0, 1) == -1) { "Built-in docs state changed" }
            bytes
        }
    }

    override suspend fun fileHash(path: String): String = hashFile(path)

    private suspend fun hashFile(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        readChunks(path) { bytes, count -> digest.update(bytes, 0, count) }
        return BuiltinDocsSync.hexDigest(digest.digest())
    }

    private suspend fun readChunks(path: String, consume: suspend (ByteArray, Int) -> Unit) {
        val remote = checkNotNull(checkedPath(path))
        check(lstat(remote)?.type == FileMode.Type.REGULAR) { "Unsafe built-in docs source" }
        checkActive()
        client.open(remote, EnumSet.of(OpenMode.READ)).use { file ->
            val bytes = ByteArray(32 * 1024)
            var offset = 0L
            while (true) {
                checkActive()
                val count = file.read(offset, bytes, 0, bytes.size)
                if (count == -1) break
                check(count > 0) { "Incomplete built-in docs source" }
                consume(bytes, count)
                offset += count
            }
        }
    }

    override suspend fun createDirectory(path: String) {
        val remote = checkNotNull(checkedPath(path, createParents = true))
        checkActive()
        client.mkdir(remote)
        if (path == BuiltinDocsSync.BACKUP_DIR || path.startsWith("${BuiltinDocsSync.BACKUP_DIR}/")) {
            checkActive()
            client.chmod(remote, 448)
        }
    }

    override suspend fun replaceAtomically(path: String, bytes: ByteArray) = replace(path, path, bytes) { file ->
        var offset = 0
        while (offset < bytes.size) {
            checkActive()
            val count = minOf(32 * 1024, bytes.size - offset)
            file.write(offset.toLong(), bytes, offset, count)
            offset += count
        }
    }

    override suspend fun backupFile(path: String, backupPath: String, expectedHash: String) =
        replace(backupPath, path) { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            readChunks(path) { bytes, count ->
                digest.update(bytes, 0, count)
                checkActive()
                file.write(offset, bytes, 0, count)
                offset += count
            }
            check(BuiltinDocsSync.hexDigest(digest.digest()) == expectedHash) {
                "Built-in docs source changed during backup"
            }
        }

    override suspend fun cleanupTemporary(path: String) {
        val relative = BuiltinDocsSync.temporaryPath(path)
        val temporary = checkedPath(relative) ?: return
        val attrs = lstat(temporary)
        if (attrs != null) {
            check(attrs.type == FileMode.Type.REGULAR) { "Unsafe built-in docs staging file" }
            checkActive()
            client.rm(temporary)
        }
        val prefix = relative.substringAfterLast('/') + ".agent-"
        val staging = checkedPath(BuiltinDocsSync.STAGING_DIR) ?: return
        check(lstat(staging)?.type == FileMode.Type.DIRECTORY) {
            "Unsafe built-in docs staging directory"
        }
        checkActive()
        client.ls(staging).filter { it.name.startsWith(prefix) }.forEach { entry ->
            check(entry.name.removePrefix(prefix).matches(Regex("[0-9a-f-]{36}")) &&
                entry.attributes.type == FileMode.Type.REGULAR) { "Unsafe built-in docs staging file" }
            checkActive()
            client.rm("$staging/${entry.name}")
        }
    }

    private suspend fun replace(
        path: String,
        temporaryOwner: String,
        bytes: ByteArray? = null,
        write: suspend (RemoteFile) -> Unit
    ) {
        val remote = checkNotNull(checkedPath(path))
        check(lstat(remote).let { it == null || it.type == FileMode.Type.REGULAR }) {
            "Unsafe built-in docs destination"
        }
        cleanupTemporary(temporaryOwner)
        val staging = checkNotNull(checkedPath(BuiltinDocsSync.STAGING_DIR, createParents = true))
        if (lstat(staging) == null) {
            checkActive()
            client.mkdir(staging)
        }
        check(lstat(staging)?.type == FileMode.Type.DIRECTORY) {
            "Unsafe built-in docs staging directory"
        }
        checkActive()
        client.chmod(staging, 448)
        val relative = BuiltinDocsSync.temporaryPath(temporaryOwner)
        var temporary = checkNotNull(checkedPath(relative))
        try {
            var viaAgent = false
            if (bytes != null) {
                // 独立名字防止 agent 超时后的迟到写入覆盖 SFTP 回退文件。
                val agentRelative = "$relative.agent-${UUID.randomUUID()}"
                val agentTemporary = checkNotNull(checkedPath(agentRelative))
                checkActive()
                if (agentWrite(agentTemporary, bytes)) {
                    check(hashFile(agentRelative) == BuiltinDocsSync.sha256(bytes)) {
                        "Built-in docs staging hash mismatch"
                    }
                    temporary = agentTemporary
                    viaAgent = true
                }
            }
            if (!viaAgent) {
                checkActive()
                client.open(temporary, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { file ->
                    checkActive()
                    client.chmod(temporary, 384)
                    write(file)
                }
            } else {
                checkActive()
                client.chmod(temporary, 384)
            }
            checkNotNull(checkedPath(path))
            check(lstat(remote).let { it == null || it.type == FileMode.Type.REGULAR }) {
                "Unsafe built-in docs destination"
            }
            checkActive()
            client.rename(temporary, remote, EnumSet.of(RenameFlags.ATOMIC, RenameFlags.OVERWRITE))
        } finally {
            runCatching { cleanupTemporary(temporaryOwner) }
        }
    }

    override suspend fun deleteFile(path: String) {
        val remote = checkedPath(path) ?: return
        val attrs = lstat(remote) ?: return
        check(attrs.type == FileMode.Type.REGULAR) { "Unsafe built-in docs removal path" }
        checkActive()
        client.rm(remote)
    }

    private suspend fun checkedPath(path: String, createParents: Boolean = false): String? {
        require(path.isEmpty() || path.split('/').all {
            it.isNotEmpty() && it != "." && it != ".." && it.none { char -> char <= '\u001f' || char == '\\' }
        }) { "Invalid built-in docs path" }
        val remote = if (path.isEmpty()) root else "$root/$path"
        var parent = ""
        for (part in remote.drop(1).split('/').dropLast(1)) {
            parent += "/$part"
            val attrs = lstat(parent)
            if (attrs == null) {
                if (!createParents) return null
                checkActive()
                client.mkdir(parent)
                check(lstat(parent)?.type == FileMode.Type.DIRECTORY) { "Unsafe built-in docs parent" }
            } else {
                check(attrs.type == FileMode.Type.DIRECTORY) { "Unsafe built-in docs parent" }
            }
        }
        return remote
    }

    private suspend fun lstat(path: String): FileAttributes? {
        checkActive()
        return try {
            client.lstat(path)
        } catch (error: SFTPException) {
            if (error.statusCode != StatusCode.NO_SUCH_FILE && error.statusCode != StatusCode.NO_SUCH_PATH) throw error
            null
        }
    }
}
