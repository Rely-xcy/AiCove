package com.aicove.core.branding

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.SYNC
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

class LocalConfigMigrationFileSystem : ConfigMigrationFileSystem {
    override fun lstat(path: String): ConfigEntry? {
        val entry = checked(path)
        val attributes = try {
            Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { return null }
        val type = when {
            attributes.isSymbolicLink -> ConfigEntryType.SYMBOLIC_LINK
            attributes.isDirectory -> ConfigEntryType.DIRECTORY
            attributes.isRegularFile -> ConfigEntryType.FILE
            else -> ConfigEntryType.OTHER
        }
        val view = Files.getFileAttributeView(entry, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        val permissions = if (type == ConfigEntryType.SYMBOLIC_LINK) null
            else view?.readAttributes()?.permissions()?.let { toMode(it) }
        return ConfigEntry(type, permissions, attributes.size())
    }

    override fun list(path: String): List<String> {
        requireType(path, ConfigEntryType.DIRECTORY)
        return Files.newDirectoryStream(checked(path)).use { entries -> entries.map { it.fileName.toString() } }
    }

    override fun realPath(path: String): String = Paths.get(path).toRealPath().toString()

    override fun mkdir(path: String) {
        val entry = checked(path)
        if (Files.getFileAttributeView(entry.parent, PosixFileAttributeView::class.java) != null) {
            Files.createDirectory(entry, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else Files.createDirectory(entry)
    }

    override fun copyFile(source: String, target: String) {
        requireType(source, ConfigEntryType.FILE)
        Files.copy(checked(source), checked(target), NOFOLLOW_LINKS, COPY_ATTRIBUTES)
    }

    override fun hashFile(path: String): String {
        requireType(path, ConfigEntryType.FILE)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(checked(path), READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(65536)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    override fun readLink(path: String): String = Files.readSymbolicLink(checked(path)).toString()

    override fun symlink(link: String, target: String) {
        try {
            Files.createSymbolicLink(checked(link), Paths.get(target))
        } catch (_: UnsupportedOperationException) { throw ConfigSymlinkUnsupportedException() }
    }

    override fun move(source: String, target: String) {
        val from = checked(source)
        val to = checked(target)
        if (lstat(target) != null) throw FileAlreadyExistsException(target)
        // Android denies getFileStore; device attributes also prevent Files.move from emulating copy/delete.
        if (Files.getAttribute(from.parent, "unix:dev", NOFOLLOW_LINKS) !=
            Files.getAttribute(to.parent, "unix:dev", NOFOLLOW_LINKS)) throw IOException("Cross-device rename")
        Files.move(from, to)
    }

    override fun setPermissions(path: String, permissions: Int) {
        val entry = checked(path)
        if (lstat(path)?.type == ConfigEntryType.SYMBOLIC_LINK) throw IOException("Link permissions are not copied")
        val view = Files.getFileAttributeView(entry, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw IOException("POSIX permissions are unavailable")
        view.setPermissions(fromMode(permissions))
    }

    override fun readSmallFile(path: String, maxBytes: Int): ByteArray {
        requireType(path, ConfigEntryType.FILE)
        val output = ByteArrayOutputStream()
        Files.newByteChannel(checked(path), READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(minOf(maxBytes + 1, 4096))
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) throw IOException("Migration record is too large")
                output.write(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        return output.toByteArray()
    }

    override fun writeSmallFile(path: String, content: ByteArray) {
        val entry = checked(path)
        val options = setOf<OpenOption>(WRITE, CREATE_NEW, NOFOLLOW_LINKS, SYNC)
        val attributes = if (Files.getFileAttributeView(entry.parent, PosixFileAttributeView::class.java) != null) {
            arrayOf<FileAttribute<*>>(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else emptyArray()
        Files.newByteChannel(entry, options, *attributes).use { channel ->
            val buffer = ByteBuffer.wrap(content)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    private fun requireType(path: String, type: ConfigEntryType) {
        if (lstat(path)?.type != type) throw IOException("Migration entry changed type")
    }

    private fun checked(path: String): Path {
        val entry = Paths.get(path).toAbsolutePath().normalize()
        var parent = entry.parent
        while (parent != null) {
            val attributes = Files.readAttributes(parent, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory || attributes.isSymbolicLink) throw IOException("Nonphysical migration parent")
            parent = parent.parent
        }
        return entry
    }

    private fun toMode(permissions: Set<PosixFilePermission>): Int = permissionBits.entries
        .filter { it.key in permissions }.fold(0) { mode, entry -> mode or entry.value }

    private fun fromMode(mode: Int): Set<PosixFilePermission> = permissionBits.entries
        .filter { (mode and it.value) != 0 }.map { it.key }.toSet()

    private companion object {
        val permissionBits = mapOf(
            PosixFilePermission.OWNER_READ to 256,
            PosixFilePermission.OWNER_WRITE to 128,
            PosixFilePermission.OWNER_EXECUTE to 64,
            PosixFilePermission.GROUP_READ to 32,
            PosixFilePermission.GROUP_WRITE to 16,
            PosixFilePermission.GROUP_EXECUTE to 8,
            PosixFilePermission.OTHERS_READ to 4,
            PosixFilePermission.OTHERS_WRITE to 2,
            PosixFilePermission.OTHERS_EXECUTE to 1
        )
    }
}
