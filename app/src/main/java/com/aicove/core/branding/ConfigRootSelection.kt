package com.aicove.core.branding

import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.util.ArrayDeque

/** A selection stays frozen while repositories can write; retries belong to the next quiescent session. */
class ConfigRootSelection(private val fs: ConfigMigrationFileSystem) {
    private val selections = mutableMapOf<Pair<String, String>, ConfigMigrationResult>()

    @Synchronized
    fun select(oldRoot: String, newRoot: String): ConfigMigrationResult {
        val roots = key(oldRoot, newRoot)
        return selections.getOrPut(roots) {
            val (old, new) = roots
            // Remote dot-roots belong to the server home, not the local container workspace.
            val workspace = old.substringBeforeLast('/').takeIf {
                fs is LocalConfigMigrationFileSystem &&
                    old.substringAfterLast('/') == BrandPaths.Legacy.CONFIG_DIR_NAME &&
                    new == "$it/${BrandPaths.CONFIG_DIR_NAME}"
            }
            val projectLinks = if (workspace != null) {
                listOf(old, new).filter { fs.lstat(it)?.type == ConfigEntryType.SYMBOLIC_LINK }
                    .associateWith { resolveProjectLink(it, workspace) }
            } else emptyMap()
            val migrationFs = object : ConfigMigrationFileSystem by fs {
                override fun realPath(path: String): String = projectLinks[path] ?: fs.realPath(path)
            }
            val result = ConfigMigration(migrationFs).migrate(old, new)
            val root = result.physicalRoot
            val rootEntry = fs.lstat(root)
            if (result.failureCode == "COMMIT_STATE_UNKNOWN") {
                throw IOException("Configuration migration commit state is ambiguous; both roots were preserved")
            }
            if (!result.completed && rootEntry?.type == ConfigEntryType.SYMBOLIC_LINK && root !in projectLinks) {
                throw IOException("Configuration migration did not validate the root link: ${result.failureCode}")
            }
            val physical = if (rootEntry?.type == ConfigEntryType.SYMBOLIC_LINK) {
                projectLinks[root] ?: fs.realPath(root)
            } else root
            if (workspace != null && physical != workspace && !physical.startsWith("$workspace/")) {
                throw IOException("Configuration root escapes the workspace")
            }
            if (fs.lstat(physical)?.type != ConfigEntryType.DIRECTORY) {
                throw IOException("Configuration root unavailable: ${result.failureCode ?: result.status}")
            }
            result.copy(physicalRoot = physical)
        }
    }

    private fun resolveProjectLink(root: String, workspace: String): String {
        val pending = ArrayDeque(root.removePrefix("$workspace/").split('/'))
        var current = workspace
        var links = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            when (part) {
                "", "." -> continue
                ".." -> {
                    if (current == workspace) throw IOException("Configuration root escapes the workspace")
                    current = current.substringBeforeLast('/')
                    continue
                }
            }
            val candidate = "$current/$part"
            val entry = fs.lstat(candidate) ?: throw IOException("Configuration root link target is unavailable")
            if (entry.type != ConfigEntryType.SYMBOLIC_LINK) {
                if (entry.type != ConfigEntryType.DIRECTORY) {
                    throw IOException("Configuration root link target is not a directory")
                }
                current = candidate
                continue
            }
            if (++links > 40) throw FileSystemLoopException(root)
            val target = fs.readLink(candidate)
            val relative = if (target.startsWith('/')) {
                val containerWorkspace = BrandPaths.CONFIG_ROOT.substringBeforeLast('/') + "/workspace"
                if (target != containerWorkspace && !target.startsWith("$containerWorkspace/")) {
                    throw IOException("Configuration root escapes the workspace")
                }
                current = workspace
                target.removePrefix(containerWorkspace)
            } else target
            relative.split('/').filter { it.isNotEmpty() }.asReversed().forEach(pending::addFirst)
        }
        if (fs.lstat(current)?.type != ConfigEntryType.DIRECTORY) {
            throw IOException("Configuration root link target is not a directory")
        }
        return current
    }

    @Synchronized
    fun existing(oldRoot: String, newRoot: String): ConfigMigrationResult =
        selections.getOrPut(key(oldRoot, newRoot)) {
            val (old, new) = key(oldRoot, newRoot)
            val root = if (fs.lstat(old) != null) old else new
            ConfigMigrationResult(ConfigMigrationStatus.INCOMPLETE, root, failureCode = "MIGRATION_SKIPPED")
        }

    private fun key(oldRoot: String, newRoot: String): Pair<String, String> =
        physicalConfigEntry(fs, oldRoot) to physicalConfigEntry(fs, newRoot)
}
