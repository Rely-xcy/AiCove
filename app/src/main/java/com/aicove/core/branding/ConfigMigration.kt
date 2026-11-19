package com.aicove.core.branding

import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.UUID

enum class ConfigMigrationStatus { MIGRATED, ALREADY_MIGRATED, COPIED_COMPATIBILITY, INCOMPLETE }

data class ConfigMigrationResult(
    val status: ConfigMigrationStatus,
    val physicalRoot: String,
    val transactionDirectory: String? = null,
    val backupDirectory: String? = null,
    /** Reserved archive location; absent on disk until the old directory has been renamed. */
    val legacyArchive: String? = null,
    val failureCode: String? = null
) {
    val completed: Boolean get() = status != ConfigMigrationStatus.INCOMPLETE
    val synchronizedAlias: Boolean
        get() = status == ConfigMigrationStatus.MIGRATED || status == ConfigMigrationStatus.ALREADY_MIGRATED
}

/**
 * Calls for the same roots must be serialized by the host, with writers stopped until this call finishes.
 * Root parents must exist; entry renames to the transaction directory require the same filesystem.
 */
class ConfigMigration(private val fs: ConfigMigrationFileSystem) {
    fun migrate(oldRoot: String, newRoot: String): ConfigMigrationResult {
        var old = oldRoot
        var new = newRoot
        var state: String? = null
        try {
            checkInterrupted()
            old = physicalEntry(oldRoot)
            new = physicalEntry(newRoot)
            if (old == new || old.startsWith("$new/") || new.startsWith("$old/")) {
                if (old != new) fail("OVERLAPPING_ROOTS")
                val resolved = if (fs.lstat(new)?.type == ConfigEntryType.SYMBOLIC_LINK) fs.realPath(new) else new
                if (fs.lstat(resolved)?.type != ConfigEntryType.DIRECTORY) fail("ROOT_NOT_DIRECTORY")
                return result(ConfigMigrationStatus.ALREADY_MIGRATED, resolved, null)
            }
            if (parent(old) != parent(new)) fail("ROOT_PARENT_MISMATCH")
            var source = fs.lstat(old)
            var destination = fs.lstat(new)
            if (source?.type == ConfigEntryType.SYMBOLIC_LINK) {
                val resolved = fs.realPath(old)
                val target = if (destination?.type == ConfigEntryType.SYMBOLIC_LINK) fs.realPath(new) else new
                if (destination != null && resolved == target && fs.lstat(resolved)?.type == ConfigEntryType.DIRECTORY) {
                    state = findState(old, new)
                    state?.let {
                        if (!hasRecord(it, "complete-", old, new)) record(it, "complete-", old, new)
                    }
                    return result(ConfigMigrationStatus.ALREADY_MIGRATED, resolved, state)
                }
                fail("SOURCE_ROOT_LINK")
            }
            if (destination?.type == ConfigEntryType.SYMBOLIC_LINK && source?.type == ConfigEntryType.DIRECTORY) {
                val resolved = runCatching { fs.realPath(new) }.getOrNull()
                if (resolved == old) return result(ConfigMigrationStatus.ALREADY_MIGRATED, old, state)
            }
            state = findState(old, new)
            if (state != null && source == null && destination?.type == ConfigEntryType.DIRECTORY &&
                hasRecord(state, "complete-", old, new)) {
                return installCompatibility(old, new, state, hadSource = false)
            }
            restoreOldEntry(old, new, state)
            source = fs.lstat(old)
            destination = fs.lstat(new)
            if (source != null && source.type != ConfigEntryType.DIRECTORY) fail("SOURCE_ROOT_TYPE")
            if (source == null && destination != null && destination.type != ConfigEntryType.DIRECTORY) {
                fail("DESTINATION_ROOT_TYPE")
            }
            if (state == null) state = createState(old, new)
            val activeState = state
            if (source?.type == ConfigEntryType.DIRECTORY && destination?.type == ConfigEntryType.DIRECTORY &&
                hasCopyCompletion(activeState, old, new)) {
                return result(ConfigMigrationStatus.COPIED_COMPATIBILITY, new, activeState)
            }
            if (source?.type == ConfigEntryType.DIRECTORY && destination == null) {
                fastRename(old, new, activeState)?.let { return it }
            }
            ensureDirectory(child(activeState, "backups"))
            ensureDirectory(child(activeState, "staging"))
            if (fs.lstat(new)?.type != ConfigEntryType.DIRECTORY) {
                if (fs.lstat(new) != null) displace(new, activeState)
                fs.mkdir(new)
            }
            val initial = if (source != null) fingerprint(old) else null
            if (source != null) {
                merge(old, new, activeState)
                if (initial != fingerprint(old) || !matches(old, new, subset = true)) fail("SOURCE_CHANGED")
            }
            return installCompatibility(old, new, activeState, source != null)
        } catch (e: Exception) {
            val code = when (e) {
                is MigrationFailure -> e.code
                is InterruptedException, is InterruptedIOException -> "INTERRUPTED"
                else -> "IO_FAILURE"
            }
            if (e is InterruptedException || e is InterruptedIOException) Thread.currentThread().interrupt()
            val recoveryFailed = try {
                restoreOldEntry(old, new, state)
                false
            } catch (_: Exception) { true }
            val fallback = try {
                val archive = state?.let { legacyArchive(it) }
                when {
                    fs.lstat(old) != null -> old
                    state != null && hasRecord(state, "complete-", old, new) &&
                        fs.lstat(new)?.type == ConfigEntryType.DIRECTORY -> new
                    archive != null && fs.lstat(archive)?.type == ConfigEntryType.DIRECTORY -> archive
                    else -> new
                }
            } catch (_: Exception) { old }
            return result(ConfigMigrationStatus.INCOMPLETE, fallback, state,
                if (recoveryFailed) "OLD_ENTRY_RECOVERY_REQUIRED" else code)
        }
    }

    private fun result(status: ConfigMigrationStatus, new: String, state: String?, code: String? = null) =
        ConfigMigrationResult(status, new, state, state?.let { child(it, "backups") },
            state?.let { legacyArchive(it) }, code)

    private fun physicalEntry(path: String): String = physicalConfigEntry(fs, path)

    private fun normalize(path: String): String {
        if (!path.startsWith('/') || '\u0000' in path) fail("INVALID_PATH")
        val segments = mutableListOf<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) fail("INVALID_PATH") else segments.removeAt(segments.lastIndex)
                else -> segments.add(it)
            }
        }
        return "/" + segments.joinToString("/")
    }

    private fun owner(old: String, new: String): ByteArray =
        ("aicove-config-migration-v1\n" + digest("$old\u0000$new".toByteArray(Charsets.UTF_8))).toByteArray(Charsets.UTF_8)

    private fun findState(old: String, new: String): String? {
        val expected = owner(old, new)
        val states = names(parent(old)).filter { it.startsWith(STATE_PREFIX) }.map { child(parent(old), it) }
            .filter { fs.lstat(it)?.type == ConfigEntryType.DIRECTORY }
            .filter {
                val marker = child(it, "request")
                val info = fs.lstat(marker)
                info?.type == ConfigEntryType.FILE && info.size <= OWNER_LIMIT &&
                    fs.readSmallFile(marker, OWNER_LIMIT).contentEquals(expected)
            }
        if (states.size > 1) fail("MULTIPLE_TRANSACTIONS")
        return states.singleOrNull()
    }

    private fun createState(old: String, new: String): String {
        val state = child(parent(old), STATE_PREFIX + randomId())
        fs.mkdir(state)
        val marker = child(state, "request")
        val content = owner(old, new)
        fs.writeSmallFile(marker, content)
        if (!fs.readSmallFile(marker, OWNER_LIMIT).contentEquals(content)) fail("JOURNAL_VERIFICATION")
        return state
    }

    private fun ensureDirectory(path: String) {
        val entry = fs.lstat(path)
        if (entry == null) fs.mkdir(path)
        else if (entry.type != ConfigEntryType.DIRECTORY) fail("JOURNAL_ENTRY_TYPE")
    }

    private fun restoreOldEntry(old: String, new: String, state: String?) {
        if (state == null || fs.lstat(old) != null || hasRecord(state, "complete-", old, new)) return
        val archive = legacyArchive(state)
        if (fs.lstat(archive)?.type == ConfigEntryType.DIRECTORY) fs.move(archive, old)
        else if (hasRecord(state, "rename-intent-", old, new) &&
            fs.lstat(new)?.type == ConfigEntryType.DIRECTORY) fs.move(new, old)
    }

    private fun fastRename(old: String, new: String, state: String): ConfigMigrationResult? {
        val alias = child(state, "alias-" + randomId())
        try {
            fs.symlink(alias, name(new))
        } catch (_: ConfigSymlinkUnsupportedException) { return null }
        if (fs.lstat(alias)?.type != ConfigEntryType.SYMBOLIC_LINK || fs.readLink(alias) != name(new)) {
            fail("ALIAS_VERIFICATION")
        }
        record(state, "rename-intent-", old, new)
        checkInterrupted()
        fs.move(old, new)
        fs.move(alias, old)
        if (fs.realPath(old) != fs.realPath(new)) fail("ALIAS_VERIFICATION")
        record(state, "complete-", old, new)
        return result(ConfigMigrationStatus.MIGRATED, new, state)
    }

    private fun names(directory: String): List<String> {
        if (fs.lstat(directory)?.type != ConfigEntryType.DIRECTORY) fail("DIRECTORY_CHANGED")
        val names = fs.list(directory)
        if (names.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\u0000' in it } ||
            names.toSet().size != names.size) fail("INVALID_DIRECTORY_LIST")
        return names.sorted()
    }

    private fun merge(source: String, target: String, state: String) {
        checkInterrupted()
        val incoming = fs.lstat(source) ?: fail("SOURCE_CHANGED")
        val existing = fs.lstat(target)
        if (incoming.type == ConfigEntryType.DIRECTORY && existing?.type == ConfigEntryType.DIRECTORY) {
            names(source).forEach { merge(child(source, it), child(target, it), state) }
            permissions(target, incoming)
            return
        }
        if (existing != null && matches(source, target, verifyPermissions = false)) {
            permissions(target, incoming)
            return
        }
        val stage = child(child(state, "staging"), randomId())
        copyTree(source, stage)
        if (!matches(source, stage)) fail("COPY_VERIFICATION")
        if (existing != null) displace(target, state)
        fs.move(stage, target)
        if (!matches(source, target)) fail("INSTALL_VERIFICATION")
    }

    private fun displace(target: String, state: String) {
        val backup = child(child(state, "backups"), randomId())
        fs.mkdir(backup)
        val origin = target.toByteArray(Charsets.UTF_8)
        if (origin.size > 8192) fail("BACKUP_PATH_TOO_LONG")
        val originFile = child(backup, "origin")
        fs.writeSmallFile(originFile, origin)
        if (!fs.readSmallFile(originFile, 8192).contentEquals(origin)) fail("BACKUP_PATH_VERIFICATION")
        val data = child(backup, "data")
        copyTree(target, data, absoluteLinks = true)
        if (!matches(target, data, compareLinkTargets = true)) fail("BACKUP_VERIFICATION")
        // Keep originals at the same depth so relative links retain their parent semantics.
        val displaced = child(parent(target), ".aicove-displaced-" + name(backup))
        val displacedBytes = displaced.toByteArray(Charsets.UTF_8)
        if (displacedBytes.size > 8192) fail("BACKUP_PATH_TOO_LONG")
        val displacedMarker = child(backup, "displaced-path")
        fs.writeSmallFile(displacedMarker, displacedBytes)
        if (!fs.readSmallFile(displacedMarker, 8192).contentEquals(displacedBytes)) fail("BACKUP_PATH_VERIFICATION")
        fs.move(target, displaced)
        if (!matches(displaced, data, compareLinkTargets = true, sourceOrigin = target)) fail("TARGET_CHANGED")
    }

    private fun copyTree(source: String, target: String, absoluteLinks: Boolean = false) {
        checkInterrupted()
        val entry = fs.lstat(source) ?: fail("SOURCE_CHANGED")
        when (entry.type) {
            ConfigEntryType.DIRECTORY -> {
                fs.mkdir(target)
                names(source).forEach { copyTree(child(source, it), child(target, it), absoluteLinks) }
                permissions(target, entry)
            }
            ConfigEntryType.FILE -> {
                fs.copyFile(source, target)
                permissions(target, entry)
            }
            ConfigEntryType.SYMBOLIC_LINK -> fs.symlink(target,
                if (absoluteLinks) linkTarget(source) else fs.readLink(source))
            ConfigEntryType.OTHER -> fail("UNSUPPORTED_FILE_TYPE")
        }
    }

    private fun permissions(path: String, entry: ConfigEntry) {
        if (entry.type != ConfigEntryType.SYMBOLIC_LINK) entry.permissions?.let { fs.setPermissions(path, it) }
    }

    private fun matches(
        source: String,
        target: String,
        subset: Boolean = false,
        verifyPermissions: Boolean = true,
        compareLinkTargets: Boolean = false,
        sourceOrigin: String = source
    ): Boolean {
        val a = fs.lstat(source) ?: return false
        val b = fs.lstat(target) ?: return false
        if (a.type != b.type) return false
        return when (a.type) {
            ConfigEntryType.FILE -> a.size == b.size && fs.hashFile(source) == fs.hashFile(target)
            ConfigEntryType.SYMBOLIC_LINK -> if (compareLinkTargets) linkTarget(source, sourceOrigin) == linkTarget(target)
                else fs.readLink(source) == fs.readLink(target)
            ConfigEntryType.DIRECTORY -> {
                val children = names(source)
                (subset || children == names(target)) &&
                    children.all { matches(child(source, it), child(target, it), subset, verifyPermissions, compareLinkTargets, child(sourceOrigin, it)) }
            }
            ConfigEntryType.OTHER -> false
        } && (!verifyPermissions || a.type == ConfigEntryType.SYMBOLIC_LINK || a.permissions == b.permissions)
    }

    private fun linkTarget(path: String, origin: String = path): String {
        val target = fs.readLink(path)
        return normalize(if (target.startsWith('/')) target else child(parent(origin), target))
    }

    private fun fingerprint(path: String): String {
        val entry = fs.lstat(path) ?: fail("SOURCE_CHANGED")
        val body = when (entry.type) {
            ConfigEntryType.FILE -> fs.hashFile(path)
            ConfigEntryType.SYMBOLIC_LINK -> fs.readLink(path)
            ConfigEntryType.DIRECTORY -> names(path).joinToString("\n") {
                digest(it.toByteArray(Charsets.UTF_8)) + ":" + fingerprint(child(path, it))
            }
            ConfigEntryType.OTHER -> fail("UNSUPPORTED_FILE_TYPE")
        }
        return digest("${entry.type}:${entry.permissions}:$body".toByteArray(Charsets.UTF_8))
    }

    private fun hasCopyCompletion(state: String, old: String, new: String): Boolean =
        hasRecord(state, "copy-complete-", old, new)

    private fun hasRecord(state: String, prefix: String, old: String, new: String): Boolean =
        names(state).filter { it.startsWith(prefix) }.any {
            val marker = child(state, it)
            val info = fs.lstat(marker)
            info?.type == ConfigEntryType.FILE && info.size <= OWNER_LIMIT &&
                fs.readSmallFile(marker, OWNER_LIMIT).contentEquals(owner(old, new))
        }

    private fun record(state: String, prefix: String, old: String, new: String) {
        val marker = child(state, prefix + randomId())
        val content = owner(old, new)
        fs.writeSmallFile(marker, content)
        if (!fs.readSmallFile(marker, OWNER_LIMIT).contentEquals(content)) fail("JOURNAL_VERIFICATION")
    }

    private fun installCompatibility(old: String, new: String, state: String, hadSource: Boolean): ConfigMigrationResult {
        val alias = child(state, "alias-" + randomId())
        try {
            fs.symlink(alias, name(new))
        } catch (_: ConfigSymlinkUnsupportedException) {
            if (!hadSource) {
                val stage = child(child(state, "staging"), randomId())
                copyTree(new, stage)
                if (!matches(new, stage)) fail("COMPATIBILITY_VERIFICATION")
                fs.move(stage, old)
                if (!matches(new, old)) fail("COMPATIBILITY_VERIFICATION")
            } else if (!matches(old, new, subset = true)) fail("COMPATIBILITY_VERIFICATION")
            record(state, "copy-complete-", old, new)
            return result(ConfigMigrationStatus.COPIED_COMPATIBILITY, new, state)
        }
        if (fs.lstat(alias)?.type != ConfigEntryType.SYMBOLIC_LINK || fs.readLink(alias) != name(new)) {
            fail("ALIAS_VERIFICATION")
        }
        if (hadSource) {
            val archive = legacyArchive(state)
            if (fs.lstat(archive) != null) fail("ARCHIVE_ALREADY_EXISTS")
            record(state, "archive-intent-", old, new)
            // This pair of renames needs recovery on failure; generic SFTP has no atomic directory/link exchange.
            checkInterrupted()
            fs.move(old, archive)
        }
        fs.move(alias, old)
        if (fs.lstat(old)?.type != ConfigEntryType.SYMBOLIC_LINK || fs.realPath(old) != new) fail("ALIAS_VERIFICATION")
        if (hadSource && !matches(legacyArchive(state), new, subset = true)) fail("ARCHIVE_VERIFICATION")
        checkInterrupted()
        record(state, "complete-", old, new)
        return result(ConfigMigrationStatus.MIGRATED, new, state)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun legacyArchive(state: String): String =
        child(parent(state), ".aicove-legacy-" + name(state).removePrefix(STATE_PREFIX))

    private fun child(path: String, name: String): String = if (path == "/") "/$name" else "$path/$name"
    private fun parent(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }
    private fun name(path: String): String = path.substringAfterLast('/')
    private fun randomId(): String = UUID.randomUUID().toString()
    private fun fail(code: String): Nothing = throw MigrationFailure(code)
    private class MigrationFailure(val code: String) : IOException()

    private companion object {
        const val STATE_PREFIX = ".aicove-migration-"
        const val OWNER_LIMIT = 256
    }
}
