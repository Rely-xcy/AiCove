package com.aicove.core.branding

import java.io.IOException

internal fun physicalConfigEntry(fs: ConfigMigrationFileSystem, path: String): String {
    val entry = path.trimEnd('/')
    val name = entry.substringAfterLast('/')
    if (!entry.startsWith('/') || '\u0000' in entry || name.isEmpty() || name == "." || name == "..") {
        throw IOException("Invalid configuration root")
    }
    val parent = fs.realPath(entry.substringBeforeLast('/').ifEmpty { "/" })
    return parent.trimEnd('/') + "/" + name
}

enum class ConfigEntryType { DIRECTORY, FILE, SYMBOLIC_LINK, OTHER }

data class ConfigEntry(val type: ConfigEntryType, val permissions: Int? = null, val size: Long = 0)

class ConfigSymlinkUnsupportedException : IOException("Symbolic links are unsupported")

/**
 * Operations must not follow the final path component, except realPath which is used only for root identity.
 * Parents passed by the migrator are physical directories. Missing entries alone return null from lstat;
 * permissions, disconnects and other I/O failures must throw. list returns names, never paths or dot entries.
 * mkdir/copyFile/writeSmallFile/move/symlink must fail if the destination already exists, including dangling links.
 * move must rename a complete entry on the same filesystem, not implement copy/delete or overwrite semantics.
 * mkdir must create owner-private directories (0700 on POSIX); writeSmallFile creates private files (0600).
 * permissions are POSIX rwx bits, or null only on filesystems without permission support.
 */
interface ConfigMigrationFileSystem {
    fun lstat(path: String): ConfigEntry?
    fun list(path: String): List<String>
    fun realPath(path: String): String
    fun mkdir(path: String)
    fun copyFile(source: String, target: String)
    fun hashFile(path: String): String
    fun readLink(path: String): String
    fun symlink(link: String, target: String)
    fun move(source: String, target: String)
    fun setPermissions(path: String, permissions: Int)
    fun readSmallFile(path: String, maxBytes: Int): ByteArray
    fun writeSmallFile(path: String, content: ByteArray)
}
