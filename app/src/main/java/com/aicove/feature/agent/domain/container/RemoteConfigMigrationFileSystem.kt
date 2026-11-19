package com.aicove.feature.agent.domain.container

import com.aicove.core.branding.ConfigEntry
import com.aicove.core.branding.ConfigEntryType
import com.aicove.core.branding.ConfigMigrationFileSystem
import com.aicove.core.branding.ConfigSymlinkUnsupportedException
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.security.MessageDigest
import java.util.EnumSet

/** The caller holds sftpOperationMutex and owns this channel for the entire migration. */
class RemoteConfigMigrationFileSystem(
    private val client: SFTPClient,
    private val checkActive: () -> Unit = {}
) : ConfigMigrationFileSystem {
    override fun lstat(path: String): ConfigEntry? {
        checked(path)
        return attributes(path)?.let {
            val type = when (it.type) {
                FileMode.Type.DIRECTORY -> ConfigEntryType.DIRECTORY
                FileMode.Type.REGULAR -> ConfigEntryType.FILE
                FileMode.Type.SYMLINK -> ConfigEntryType.SYMBOLIC_LINK
                else -> ConfigEntryType.OTHER
            }
            ConfigEntry(type, if (type == ConfigEntryType.SYMBOLIC_LINK) null else it.mode.permissionsMask and 511, it.size)
        }
    }

    override fun list(path: String): List<String> {
        requireType(path, ConfigEntryType.DIRECTORY)
        return client.ls(path).map { it.name }.filter { it != "." && it != ".." }
    }

    override fun realPath(path: String): String {
        checkActive()
        return client.canonicalize(path)
    }

    override fun mkdir(path: String) {
        absent(path)
        client.getSFTPEngine().makeDir(path, FileAttributes.Builder().withPermissions(448).build())
    }

    override fun copyFile(source: String, target: String) {
        requireType(source, ConfigEntryType.FILE)
        absent(target)
        val attributes = attributes(source) ?: throw IOException("Source disappeared")
        if (attributes.type != FileMode.Type.REGULAR) throw IOException("Migration entry changed type")
        client.open(source, EnumSet.of(OpenMode.READ)).use { input ->
            client.open(target, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), privateFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var offset = 0L
                while (true) {
                    checkActive()
                    val count = input.read(offset, buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) throw IOException("Empty SFTP read")
                    output.write(offset, buffer, 0, count)
                    offset += count
                }
                output.setAttributes(FileAttributes.Builder().withPermissions(attributes.mode.permissionsMask and 511)
                    .withAtimeMtime(attributes.atime, attributes.mtime).build())
            }
        }
    }

    override fun hashFile(path: String): String {
        requireType(path, ConfigEntryType.FILE)
        val digest = MessageDigest.getInstance("SHA-256")
        readChunks(path) { buffer, count -> digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    override fun readLink(path: String): String {
        requireType(path, ConfigEntryType.SYMBOLIC_LINK)
        return client.readlink(path)
    }

    override fun symlink(link: String, target: String) {
        absent(link)
        if (client.version() < 3) throw ConfigSymlinkUnsupportedException()
        try { client.symlink(link, target) }
        catch (error: SFTPException) {
            if (error.statusCode == StatusCode.OP_UNSUPPORTED) throw ConfigSymlinkUnsupportedException()
            throw error
        }
    }

    override fun move(source: String, target: String) {
        checked(source)
        absent(target)
        client.rename(source, target)
    }

    override fun setPermissions(path: String, permissions: Int) {
        if (lstat(path)?.type == ConfigEntryType.SYMBOLIC_LINK) throw IOException("Link permissions are not copied")
        client.chmod(path, permissions)
    }

    override fun readSmallFile(path: String, maxBytes: Int): ByteArray {
        requireType(path, ConfigEntryType.FILE)
        val output = ByteArrayOutputStream()
        readChunks(path) { buffer, count ->
            if (output.size().toLong() + count > maxBytes) throw IOException("Migration record is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    override fun writeSmallFile(path: String, content: ByteArray) {
        absent(path)
        client.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), privateFile).use { output ->
            var offset = 0
            while (offset < content.size) {
                checkActive()
                val count = minOf(BUFFER_SIZE, content.size - offset)
                output.write(offset.toLong(), content, offset, count)
                offset += count
            }
        }
    }

    private fun readChunks(path: String, consume: (ByteArray, Int) -> Unit) {
        client.open(path, EnumSet.of(OpenMode.READ)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var offset = 0L
            while (true) {
                checkActive()
                val count = input.read(offset, buffer, 0, buffer.size)
                if (count < 0) break
                if (count == 0) throw IOException("Empty SFTP read")
                consume(buffer, count)
                offset += count
            }
        }
    }

    private fun attributes(path: String): FileAttributes? {
        checkActive()
        return try { client.lstat(path) } catch (error: SFTPException) {
            if (error.statusCode == StatusCode.NO_SUCH_FILE) null else throw error
        }
    }

    private fun requireType(path: String, type: ConfigEntryType) {
        if (lstat(path)?.type != type) throw IOException("Migration entry changed type")
    }

    private fun absent(path: String) {
        if (lstat(path) != null) throw FileAlreadyExistsException(path)
    }

    private fun checked(path: String) {
        checkActive()
        if (!path.startsWith('/') || '\u0000' in path || path.split('/').any { it == "." || it == ".." }) {
            throw IOException("Nonphysical migration path")
        }
        var parent = path.substringBeforeLast('/').ifEmpty { "/" }
        while (true) {
            if (attributes(parent)?.type != FileMode.Type.DIRECTORY) throw IOException("Nonphysical migration parent")
            if (parent == "/") break
            parent = parent.substringBeforeLast('/').ifEmpty { "/" }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 32768
        val privateFile: FileAttributes = FileAttributes.Builder().withPermissions(384).build()
    }
}
