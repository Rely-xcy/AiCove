package com.aicove.core.branding

import java.io.IOException

/** A selection stays frozen while repositories can write; retries belong to the next quiescent session. */
class ConfigRootSelection(private val fs: ConfigMigrationFileSystem) {
    private val selections = mutableMapOf<Pair<String, String>, ConfigMigrationResult>()

    @Synchronized
    fun select(oldRoot: String, newRoot: String): ConfigMigrationResult =
        selections.getOrPut(key(oldRoot, newRoot)) {
            val result = ConfigMigration(fs).migrate(oldRoot, newRoot)
            val root = result.physicalRoot
            val rootEntry = fs.lstat(root)
            if (!result.completed && rootEntry?.type == ConfigEntryType.SYMBOLIC_LINK) {
                throw IOException("Configuration migration did not validate the root link: ${result.failureCode}")
            }
            val physical = if (rootEntry?.type == ConfigEntryType.SYMBOLIC_LINK) fs.realPath(root) else root
            if (fs.lstat(physical)?.type != ConfigEntryType.DIRECTORY) {
                throw IOException("Configuration root unavailable: ${result.failureCode ?: result.status}")
            }
            result.copy(physicalRoot = physical)
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
