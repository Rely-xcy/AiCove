package com.aicove.core.branding

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val local = LocalConfigMigrationFileSystem()

    @Test
    fun oldInstallationIsVerifiedArchivedAndAliased() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "agent/settings.json", "old settings")
        val result = migrate(old, new)

        assertEquals(ConfigMigrationStatus.MIGRATED, result.status)
        assertTrue(result.completed)
        assertTrue(result.synchronizedAlias)
        assertEquals("old settings", File(new, "agent/settings.json").readText())
        assertEquals("old settings", File(result.legacyArchive!!, "agent/settings.json").readText())
        assertEquals(new.canonicalPath, old.canonicalPath)
        write(old, "through-old", "shared")
        assertEquals("shared", File(new, "through-old").readText())
        assertEquals(ConfigMigrationStatus.ALREADY_MIGRATED, migrate(old, new).status)
    }

    @Test
    fun emptyNewInstallationCreatesBothEntrances() {
        val old = File(temp.root, "old")
        val new = File(temp.root, "new")
        assertEquals(ConfigMigrationStatus.MIGRATED, migrate(old, new).status)
        assertTrue(new.isDirectory)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals(new.name, Files.readSymbolicLink(old.toPath()).toString())
        assertEquals(new.canonicalPath, old.canonicalPath)
    }

    @Test
    fun newOnlyInstallationKeepsNewData() {
        val old = File(temp.root, "old")
        val new = temp.newFolder("new")
        write(new, "new-only", "retained")
        assertTrue(migrate(old, new).completed)
        assertEquals("retained", File(old, "new-only").readText())
    }

    @Test
    fun sameContentNeedsNoConflictBackupAndKeepsNewOnlyFiles() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "same", "same")
        write(new, "same", "same")
        write(new, "new-only", "keep")
        val result = migrate(old, new)
        assertTrue(result.completed)
        assertTrue(File(result.backupDirectory!!).listFiles()!!.isEmpty())
        assertEquals("keep", File(new, "new-only").readText())
    }

    @Test
    fun conflictingTargetIsBackedUpBeforeOldContentWins() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "secret", "old private content")
        write(new, "secret", "new private content")
        val result = migrate(old, new)
        assertTrue(result.completed)
        assertEquals("old private content", File(new, "secret").readText())
        assertEquals("old private content", File(result.legacyArchive!!, "secret").readText())
        assertTrue(backupContents(result).contains("new private content"))
        val backup = File(result.backupDirectory!!).listFiles()!!.single()
        assertEquals(File(new, "secret").absolutePath, File(backup, "origin").readText())
        assertFalse(result.toString().contains("private content"))
    }

    @Test
    fun bothFileDirectoryConflictDirectionsAreLossless() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "directory/old-file", "old subtree")
        write(new, "directory", "new file")
        write(old, "file", "old file")
        write(new, "file/new-child", "new subtree")
        val result = migrate(old, new)
        assertTrue(result.completed)
        assertEquals("old subtree", File(new, "directory/old-file").readText())
        assertEquals("old file", File(new, "file").readText())
        assertTrue(backupContents(result).containsAll(listOf("new file", "new subtree")))
    }

    @Test
    fun targetRootFileIsBackedUpBeforeCreatingDirectory() {
        val old = temp.newFolder("old")
        val new = write(temp.root, "new", "root conflict")
        write(old, "settings", "old")
        val result = migrate(old, new)
        assertTrue(result.completed)
        assertTrue(new.isDirectory)
        assertTrue(backupContents(result).contains("root conflict"))
    }

    @Test
    fun externalDanglingAndCyclicLinksAreCopiedWithoutFollowing() {
        val outside = temp.newFolder("outside")
        write(outside, "untouched", "outside")
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        Files.createSymbolicLink(File(old, "external").toPath(), outside.toPath())
        Files.createSymbolicLink(File(old, "dangling").toPath(), File("missing").toPath())
        Files.createSymbolicLink(File(old, "cycle").toPath(), File("cycle").toPath())
        write(new, "external", "target conflict")
        val result = migrate(old, new)
        assertTrue(result.completed)
        listOf("external", "dangling", "cycle").forEach { name ->
            assertTrue(Files.isSymbolicLink(File(new, name).toPath()))
            assertEquals(
                Files.readSymbolicLink(File(result.legacyArchive!!, name).toPath()),
                Files.readSymbolicLink(File(new, name).toPath())
            )
        }
        assertEquals("outside", File(outside, "untouched").readText())
        assertTrue(backupContents(result).contains("target conflict"))
    }

    @Test
    fun destinationDirectoryLinkIsDisplacedRatherThanTraversed() {
        val outside = temp.newFolder("outside")
        write(outside, "untouched", "outside")
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "linked/inside", "old")
        Files.createSymbolicLink(File(new, "linked").toPath(), outside.toPath())
        assertTrue(migrate(old, new).completed)
        assertFalse(Files.isSymbolicLink(File(new, "linked").toPath()))
        assertEquals("old", File(new, "linked/inside").readText())
        assertEquals(listOf("untouched"), outside.list()!!.toList())
    }

    @Test
    fun danglingDestinationRootIsPreservedAsABackupLink() {
        val old = temp.newFolder("old")
        val new = File(temp.root, "new")
        write(old, "settings", "old")
        Files.createSymbolicLink(new.toPath(), File("missing").toPath())
        val result = migrate(old, new)
        assertTrue(result.completed)
        val backups = File(result.backupDirectory!!).listFiles()!!
        assertTrue(backups.any { Files.isSymbolicLink(File(it, "data").toPath()) })
    }

    @Test
    fun unrelatedSourceRootLinkIsRejectedWithoutReadingIt() {
        val outside = temp.newFolder("outside")
        write(outside, "private", "outside")
        val old = File(temp.root, "old")
        val new = File(temp.root, "new")
        Files.createSymbolicLink(old.toPath(), outside.toPath())
        assertFalse(migrate(old, new).completed)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertFalse(new.exists())
    }

    @Test
    fun equivalentRootAliasIsAlreadyMigrated() {
        val new = temp.newFolder("new")
        val old = File(temp.root, "old")
        Files.createSymbolicLink(old.toPath(), new.toPath())
        assertEquals(ConfigMigrationStatus.ALREADY_MIGRATED, migrate(old, new).status)
    }

    @Test
    fun identicalRootIsANoopAndNestedRootsAreRejected() {
        val old = temp.newFolder("old")
        assertEquals(ConfigMigrationStatus.ALREADY_MIGRATED, migrate(old, old).status)
        write(old, "settings", "old")
        assertFalse(migrate(old, File(old, "nested")).completed)
        assertFalse(File(old, "nested").exists())
    }

    @Test
    fun partialCopyFailureKeepsOldAndIntermediateFilesForRetry() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "secret", "old private content")
        write(new, "secret", "new private content")
        val failing = object : ConfigMigrationFileSystem by local {
            override fun copyFile(source: String, target: String) {
                local.writeSmallFile(target, byteArrayOf(1))
                throw IOException("old private content must not escape")
            }
        }
        val first = migrate(old, new, failing)
        assertFalse(first.completed)
        assertTrue(old.isDirectory)
        assertEquals("old private content", File(old, "secret").readText())
        assertEquals("new private content", File(new, "secret").readText())
        assertNotNull(first.transactionDirectory)
        assertFalse(first.toString().contains("private content"))
        val state = File(first.transactionDirectory!!)
        val countBeforeRetry = state.walkTopDown().count()
        val second = migrate(old, new)
        assertTrue(second.completed)
        assertEquals(first.transactionDirectory, second.transactionDirectory)
        assertTrue(state.walkTopDown().count() > countBeforeRetry)
        assertTrue(backupContents(second).contains("new private content"))
    }

    @Test
    fun corruptedCopyIsNeverPublished() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "secret", "old")
        write(new, "secret", "new")
        val corrupt = object : ConfigMigrationFileSystem by local {
            override fun copyFile(source: String, target: String) {
                local.writeSmallFile(target, "corrupt".toByteArray())
            }
        }
        assertFalse(migrate(old, new, corrupt).completed)
        assertEquals("old", File(old, "secret").readText())
        assertEquals("new", File(new, "secret").readText())
        assertTrue(migrate(old, new).completed)
    }

    @Test
    fun failureAfterDisplacementPreservesExistingVerifiedBackup() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "secret", "old")
        write(new, "secret", "new")
        val failing = object : ConfigMigrationFileSystem by local {
            override fun move(source: String, target: String) {
                if (target == File(new, "secret").absolutePath) throw IOException("offline")
                local.move(source, target)
            }
        }
        val first = migrate(old, new, failing)
        assertFalse(first.completed)
        assertEquals("old", File(old, "secret").readText())
        val originalBackups = File(first.backupDirectory!!).listFiles()!!.map { it.name }.toSet()
        assertTrue(backupContents(first).contains("new"))
        val second = migrate(old, new)
        assertTrue(second.completed)
        assertTrue(File(second.backupDirectory!!).listFiles()!!.map { it.name }.containsAll(originalBackups))
        assertTrue(backupContents(second).contains("new"))
    }

    @Test
    fun interruptedAliasCommitKeepsNewRootAndRepairsLegacyLinkOnRetry() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "old")
        val failing = object : ConfigMigrationFileSystem by local {
            override fun move(source: String, target: String) {
                if (target == old.absolutePath && !File(source).name.startsWith(".aicove-legacy-")) {
                    throw InterruptedIOException("interrupted")
                }
                local.move(source, target)
            }
        }
        val first = migrate(old, new, failing)
        assertTrue(Thread.interrupted())
        assertFalse(first.completed)
        assertFalse(old.exists())
        assertEquals("old", File(new, "settings").readText())
        write(new, "settings", "edited after interrupted commit")

        assertTrue(migrate(old, new).completed)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals("edited after interrupted commit", File(old, "settings").readText())
    }

    @Test
    fun retryRecoversAnInterruptedArchiveRename() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "old")
        val first = migrate(old, new, object : ConfigMigrationFileSystem by local {
            override fun move(source: String, target: String) {
                local.move(source, target)
                if (File(target).name.startsWith(".aicove-legacy-") && source == old.absolutePath) {
                    throw InterruptedIOException("connection lost after archive rename")
                }
            }
        })
        assertTrue("The failed attempt must preserve interruption", Thread.interrupted())
        assertFalse(first.completed)
        val archive = File(first.legacyArchive!!)
        assertTrue(Files.isDirectory(archive.toPath()))
        assertEquals("old", File(archive, "settings").readText())
        write(new, "settings", "edited after archive rename")

        val recovered = migrate(old, new)

        assertTrue(recovered.completed)
        assertEquals(first.transactionDirectory, recovered.transactionDirectory)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals("edited after archive rename", File(old, "settings").readText())
        assertEquals("old", File(archive, "settings").readText())
    }

    @Test
    fun unsupportedSymlinkReportsNonsynchronizedCopyCompatibility() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "old")
        write(new, "settings", "new")
        val unsupported = object : ConfigMigrationFileSystem by local {
            override fun symlink(link: String, target: String) {
                throw ConfigSymlinkUnsupportedException()
            }
        }
        val result = migrate(old, new, unsupported)
        assertEquals(ConfigMigrationStatus.COPIED_COMPATIBILITY, result.status)
        assertTrue(result.completed)
        assertFalse(result.synchronizedAlias)
        assertFalse(Files.isSymbolicLink(old.toPath()))
        assertEquals("old", File(old, "settings").readText())
        assertEquals("old", File(new, "settings").readText())
        write(new, "settings", "not synchronized")
        assertEquals("old", File(old, "settings").readText())
        assertTrue(backupContents(result).contains("new"))
    }

    @Test
    fun unsupportedSymlinkNewInstallationCopiesNewDataToOldEntrance() {
        val old = File(temp.root, "old")
        val new = temp.newFolder("new")
        write(new, "settings", "new only")
        val result = migrate(old, new, object : ConfigMigrationFileSystem by local {
            override fun symlink(link: String, target: String) {
                throw ConfigSymlinkUnsupportedException()
            }
        })
        assertEquals(ConfigMigrationStatus.COPIED_COMPATIBILITY, result.status)
        assertEquals("new only", File(old, "settings").readText())
    }

    @Test
    fun permissionErrorsAreNotDowngradedToUnsupportedLinks() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "old")
        val result = migrate(old, new, object : ConfigMigrationFileSystem by local {
            override fun symlink(link: String, target: String) {
                throw IOException("permission denied")
            }
        })
        assertEquals(ConfigMigrationStatus.INCOMPLETE, result.status)
        assertEquals("old", File(old, "settings").readText())
    }

    @Test
    fun executableAndPrivatePermissionsArePreserved() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        val script = write(old, "bin/tool", "run")
        Files.setPosixFilePermissions(script.toPath(), PosixFilePermissions.fromString("rwx------"))
        val privateFile = write(old, "private", "private")
        Files.setPosixFilePermissions(privateFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        assertTrue(migrate(old, new).completed)
        listOf("bin/tool", "private").forEach { name ->
            assertEquals(
                Files.getPosixFilePermissions(File(resultArchive(old), name).toPath(), NOFOLLOW_LINKS),
                Files.getPosixFilePermissions(File(new, name).toPath(), NOFOLLOW_LINKS)
            )
        }
    }

    @Test
    fun transactionLikeUserContentIsNotReusedOrOverwritten() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        val user = temp.newFolder(".aicove-migration-user-content")
        write(user, "request", "not a migration record")
        write(new, ".aicove-migration-conflicts/keep", "user data")
        write(old, "settings", "old")
        assertTrue(migrate(old, new).completed)
        assertEquals("not a migration record", File(user, "request").readText())
        assertEquals("user data", File(new, ".aicove-migration-conflicts/keep").readText())
    }

    @Test
    fun bothRootsLinkingToTheSameDirectoryAreAlreadyMigrated() {
        val physical = temp.newFolder("physical")
        val old = File(temp.root, "old")
        val new = File(temp.root, "new")
        Files.createSymbolicLink(old.toPath(), physical.toPath())
        Files.createSymbolicLink(new.toPath(), physical.toPath())
        val result = migrate(old, new)
        assertEquals(ConfigMigrationStatus.ALREADY_MIGRATED, result.status)
        assertEquals(physical.canonicalPath, result.physicalRoot)
        assertEquals(ConfigMigrationStatus.ALREADY_MIGRATED, migrate(old, old).status)
    }

    @Test
    fun corruptBackupDoesNotDisplaceTheOriginalTarget() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "old")
        write(new, "settings", "new")
        val fs = object : ConfigMigrationFileSystem by local {
            override fun copyFile(source: String, target: String) {
                if (source == File(new, "settings").absolutePath) {
                    local.writeSmallFile(target, "corrupt backup".toByteArray())
                } else local.copyFile(source, target)
            }
        }
        assertFalse(migrate(old, new, fs).completed)
        assertEquals("new", File(new, "settings").readText())
        assertEquals("old", File(old, "settings").readText())
        assertTrue(migrate(old, new).completed)
    }

    @Test
    fun failedNewInstallationCompatibilityCopyIsNotAnOldSourceOnRetry() {
        val old = File(temp.root, "old")
        val new = temp.newFolder("new")
        write(new, "settings", "new only")
        val fs = object : ConfigMigrationFileSystem by local {
            override fun symlink(link: String, target: String) { throw ConfigSymlinkUnsupportedException() }
            override fun copyFile(source: String, target: String) {
                local.writeSmallFile(target, "partial".toByteArray())
                throw IOException("offline")
            }
        }
        assertFalse(migrate(old, new, fs).completed)
        assertFalse(old.exists())
        assertEquals("new only", File(new, "settings").readText())
        val unsupported = object : ConfigMigrationFileSystem by local {
            override fun symlink(link: String, target: String) { throw ConfigSymlinkUnsupportedException() }
        }
        assertTrue(migrate(old, new, unsupported).completed)
        assertEquals("new only", File(old, "settings").readText())
    }

    @Test
    fun completedCopyCompatibilityDoesNotReplayStaleOldDataOnStartup() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "old")
        val unsupported = object : ConfigMigrationFileSystem by local {
            override fun symlink(link: String, target: String) { throw ConfigSymlinkUnsupportedException() }
        }
        assertTrue(migrate(old, new, unsupported).completed)
        write(new, "settings", "edited after migration")
        assertEquals(ConfigMigrationStatus.COPIED_COMPATIBILITY, migrate(old, new, unsupported).status)
        assertEquals("edited after migration", File(new, "settings").readText())
        assertEquals("old", File(old, "settings").readText())
    }

    @Test
    fun partiallyWrittenArchiveIntentCanBeRetriedWithoutOverwritingIt() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "old")
        val failing = object : ConfigMigrationFileSystem by local {
            override fun writeSmallFile(path: String, content: ByteArray) {
                if (File(path).name.startsWith("archive-intent-")) {
                    local.writeSmallFile(path, byteArrayOf(1))
                    throw IOException("offline")
                } else local.writeSmallFile(path, content)
            }
        }
        val first = migrate(old, new, failing)
        assertFalse(first.completed)
        val partial = File(first.transactionDirectory!!).listFiles()!!.first { it.name.startsWith("archive-intent-") }
        assertTrue(migrate(old, new).completed)
        assertTrue(partial.readBytes().contentEquals(byteArrayOf(1)))
    }

    @Test
    fun identicalBytesUseOldPermissionsWithoutCreatingABackup() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        val source = write(old, "settings", "same")
        val target = write(new, "settings", "same")
        Files.setPosixFilePermissions(source.toPath(), PosixFilePermissions.fromString("rw-------"))
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        val result = migrate(old, new)
        assertTrue(result.completed)
        assertTrue(File(result.backupDirectory!!).listFiles()!!.isEmpty())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target.toPath()))
    }

    @Test
    fun unsupportedSpecialTypeFailsWithoutChangingTheSource() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        val special = write(old, "special", "untouched")
        val fs = object : ConfigMigrationFileSystem by local {
            override fun lstat(path: String): ConfigEntry? =
                if (path == special.absolutePath) ConfigEntry(ConfigEntryType.OTHER) else local.lstat(path)
        }
        val result = migrate(old, new, fs)
        assertFalse(result.completed)
        assertEquals("UNSUPPORTED_FILE_TYPE", result.failureCode)
        assertEquals("untouched", special.readText())
    }

    @Test
    fun localPrimitivesNeverOverwriteExistingEntriesOrFollowFileLinks() {
        val root = temp.newFolder("local")
        val source = write(root, "source", "source")
        val target = write(root, "target", "target")
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        listOf<() -> Unit>(
            { local.copyFile(source.absolutePath, target.absolutePath) },
            { local.move(source.absolutePath, target.absolutePath) },
            { local.writeSmallFile(target.absolutePath, "overwrite".toByteArray()) },
            { local.hashFile(link.absolutePath) }
        ).forEach { operation ->
            var rejected = false
            try { operation() } catch (_: IOException) { rejected = true }
            assertTrue(rejected)
        }
        assertEquals("source", source.readText())
        assertEquals("target", target.readText())
    }

    @Test
    fun oldOnlyRenamesTheOriginalEntityWithoutScanningOrHashingItsTree() {
        val old = temp.newFolder("old")
        val new = File(temp.root, "new")
        write(old, "plugins/large/data", "original")
        val inode = Files.getAttribute(old.toPath(), "unix:ino", NOFOLLOW_LINKS)
        val fs = object : ConfigMigrationFileSystem by local {
            override fun list(path: String): List<String> {
                if (path == old.absolutePath || path.startsWith("${old.absolutePath}/") ||
                    path == new.absolutePath || path.startsWith("${new.absolutePath}/")) {
                    throw AssertionError("Fast path must not scan the tree")
                }
                return local.list(path)
            }
            override fun hashFile(path: String): String = throw AssertionError("Fast path must not hash")
            override fun copyFile(source: String, target: String) = throw AssertionError("Fast path must not copy")
        }
        val result = migrate(old, new, fs)
        assertTrue(result.completed)
        assertEquals(inode, Files.getAttribute(new.toPath(), "unix:ino", NOFOLLOW_LINKS))
        assertEquals("original", File(old, "plugins/large/data").readText())
        assertEquals(ConfigMigrationStatus.ALREADY_MIGRATED, migrate(old, new, fs).status)
    }

    @Test
    fun fastRenameAliasFailureKeepsNewRootAndNextAttemptUsesTheSameJournal() {
        val old = temp.newFolder("old")
        val new = File(temp.root, "new")
        write(old, "settings", "old")
        var failAlias = true
        val fs = object : ConfigMigrationFileSystem by local {
            override fun move(source: String, target: String) {
                if (failAlias && target == old.absolutePath) {
                    failAlias = false
                    throw IOException("Alias commit failed")
                }
                local.move(source, target)
            }
        }
        val first = migrate(old, new, fs)
        assertFalse(first.completed)
        assertEquals("IO_FAILURE", first.failureCode)
        assertEquals(new.absolutePath, first.physicalRoot)
        assertFalse(Files.exists(old.toPath(), NOFOLLOW_LINKS))
        assertEquals("old", File(new, "settings").readText())
        assertNotNull(first.transactionDirectory)
        write(new, "settings", "edited after fast rename")

        val second = migrate(old, new)

        assertEquals(ConfigMigrationStatus.MIGRATED, second.status)
        assertEquals(first.transactionDirectory, second.transactionDirectory)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals(new.canonicalPath, old.canonicalPath)
        assertEquals("edited after fast rename", File(old, "settings").readText())
    }

    @Test
    fun archivesAndDisplacedOriginalsKeepRelativeExternalLinkSemantics() {
        write(temp.root, "external", "external data")
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        Files.createSymbolicLink(File(old, "external").toPath(), File("../external").toPath())
        write(old, "conflict", "old wins")
        Files.createSymbolicLink(File(new, "conflict").toPath(), File("../external").toPath())
        val result = migrate(old, new)
        assertTrue(result.completed)
        assertEquals("external data", File(result.legacyArchive!!, "external").readText())
        val backup = File(result.backupDirectory!!).listFiles()!!.single()
        val displaced = File(File(backup, "displaced-path").readText())
        assertEquals(new, displaced.parentFile)
        assertEquals("external data", displaced.readText())
        assertEquals("external data", File(backup, "data").readText())
        assertEquals("old wins", File(old, "conflict").readText())
    }

    @Test
    fun interruptedAliasCommitUsesTheNewRootAndPreservesTheArchive() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "old")
        val first = migrate(old, new, object : ConfigMigrationFileSystem by local {
            override fun move(source: String, target: String) {
                if (target == old.absolutePath) throw IOException("Legacy alias cannot be installed")
                local.move(source, target)
            }
        })
        assertFalse(first.completed)
        assertEquals(new.absolutePath, first.physicalRoot)
        assertEquals("old", File(first.legacyArchive!!, "settings").readText())
        write(new, "settings", "new changed independently")
        assertEquals("old", File(first.legacyArchive, "settings").readText())
        assertTrue(migrate(old, new).completed)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals("new changed independently", File(old, "settings").readText())
    }

    @Test
    fun interruptedFastRenameIsRecoveredFromItsJournalOnNextStartup() {
        val old = temp.newFolder("old")
        val new = File(temp.root, "new")
        write(old, "settings", "original")
        val first = migrate(old, new, object : ConfigMigrationFileSystem by local {
            override fun move(source: String, target: String) {
                if (target == new.absolutePath) throw IOException("Stopped before rename")
                local.move(source, target)
            }
        })
        assertFalse(first.completed)
        local.move(old.absolutePath, new.absolutePath)
        assertFalse(old.exists())
        val result = migrate(old, new)
        assertTrue(result.completed)
        assertEquals(first.transactionDirectory, result.transactionDirectory)
        assertEquals("original", File(old, "settings").readText())
        assertTrue(Files.isSymbolicLink(old.toPath()))
    }

    @Test
    fun destinationRootLinkCycleIsBackedUpWithoutFollowingIt() {
        val old = temp.newFolder("old")
        val new = File(temp.root, "new")
        write(old, "settings", "original")
        Files.createSymbolicLink(new.toPath(), File("new").toPath())
        val result = migrate(old, new)
        assertTrue(result.completed)
        assertEquals("original", File(new, "settings").readText())
        val backup = File(result.backupDirectory!!).listFiles()!!.single()
        assertTrue(Files.isSymbolicLink(File(backup, "data").toPath()))
        assertTrue(Files.isSymbolicLink(File(File(backup, "displaced-path").readText()).toPath()))
    }

    @Test
    fun completedMergeRepairsMissingAliasWithoutReplayingTheArchivedSnapshot() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "before migration")
        val first = migrate(old, new)
        assertTrue(first.completed)
        write(new, "settings", "edited after migration")
        Files.delete(old.toPath())

        val restarted = migrate(old, new)

        assertTrue(restarted.completed)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals("edited after migration", File(old, "settings").readText())
        assertEquals("before migration", File(first.legacyArchive!!, "settings").readText())
        assertTrue(File(first.backupDirectory!!).listFiles()!!.isEmpty())
    }

    @Test
    fun restoredLegacyDirectoryCannotOverwriteAnAlreadyCommittedRoot() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "original")
        val first = migrate(old, new)
        assertTrue(first.completed)
        write(new, "settings", "current value")
        Files.delete(old.toPath())
        write(old, "settings", "restored stale copy")

        val retry = migrate(old, new)

        assertFalse(retry.completed)
        assertEquals("COMMITTED_ROOT_CONFLICT", retry.failureCode)
        assertEquals("current value", File(new, "settings").readText())
        assertEquals("restored stale copy", File(old, "settings").readText())
    }

    @Test
    fun failedCommitMarkerRestoresOldRootBeforePublishingAlias() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "before migration")
        val failed = migrate(old, new, object : ConfigMigrationFileSystem by local {
            override fun writeSmallFile(path: String, content: ByteArray) {
                if (File(path).name.startsWith("commit-ready-")) throw IOException("Commit record failed")
                local.writeSmallFile(path, content)
            }
        })
        assertFalse(failed.completed)
        assertTrue(old.isDirectory)
        assertFalse(Files.isSymbolicLink(old.toPath()))
        assertEquals("before migration", File(old, "settings").readText())
        write(new, "settings", "edited before retry")

        assertTrue(migrate(old, new).completed)
        assertEquals("before migration", File(new, "settings").readText())
    }

    @Test
    fun failedCompletionMarkerRepairsMissingAliasWithoutReplayingArchive() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "before migration")
        val failed = migrate(old, new, object : ConfigMigrationFileSystem by local {
            override fun writeSmallFile(path: String, content: ByteArray) {
                if (File(path).name.startsWith("complete-")) throw IOException("Completion record failed")
                local.writeSmallFile(path, content)
            }
        })
        assertFalse(failed.completed)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        write(new, "settings", "edited after alias commit")
        Files.delete(old.toPath())

        assertTrue(migrate(old, new).completed)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals("edited after alias commit", File(old, "settings").readText())
    }

    @Test
    fun lexicallyEquivalentDanglingDestinationIsNotReportedAsAnAlias() {
        val old = temp.newFolder("old")
        val new = File(temp.root, "new")
        write(old, "settings", "original")
        Files.createSymbolicLink(new.toPath(), File("missing/../old").toPath())
        assertFalse(new.exists())

        val result = migrate(old, new)

        assertEquals(ConfigMigrationStatus.MIGRATED, result.status)
        assertFalse(Files.isSymbolicLink(new.toPath()))
        assertEquals("original", File(new, "settings").readText())
        assertTrue(File(result.backupDirectory!!).listFiles()!!.any {
            Files.isSymbolicLink(File(it, "data").toPath())
        })
    }

    @Test
    fun parentTraversalAfterASymlinkUsesThePhysicalWorkspaceForSelectionAndMigration() {
        val logical = temp.newFolder("logical")
        val physical = temp.newFolder("physical")
        val team = File(physical, "team").apply { mkdir() }
        val logicalProject = File(logical, "project").apply { mkdir() }
        val physicalProject = File(physical, "project").apply { mkdir() }
        Files.createSymbolicLink(File(logical, "link").toPath(), team.toPath())
        write(logicalProject, "old/settings", "untouched logical workspace")
        write(physicalProject, "old/settings", "physical workspace")
        val entry = File(logical, "link/../project")

        val selected = ConfigRootSelection(local).select(File(entry, "old").path, File(entry, "new").path)

        assertTrue(selected.completed)
        assertEquals(File(physicalProject, "new").canonicalPath, selected.physicalRoot)
        assertEquals("physical workspace", File(selected.physicalRoot, "settings").readText())
        assertEquals("untouched logical workspace", File(logicalProject, "old/settings").readText())
        assertFalse(File(logicalProject, "new").exists())
    }

    @Test
    fun ambiguousLegacyArchiveIsPreservedInsteadOfReplayed() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        write(old, "settings", "original")
        write(new, "settings", "new value")
        val incomplete = migrate(old, new, object : ConfigMigrationFileSystem by local {
            override fun copyFile(source: String, target: String) {
                throw IOException("pause before migration commit")
            }
        })
        assertFalse(incomplete.completed)
        local.move(old.absolutePath, incomplete.legacyArchive!!)

        val retried = migrate(old, new)

        assertFalse(retried.completed)
        assertEquals("COMMIT_STATE_UNKNOWN", retried.failureCode)
        assertEquals("original", File(incomplete.legacyArchive, "settings").readText())
        assertEquals("new value", File(new, "settings").readText())
        assertFalse(old.exists())
    }

    @Test
    fun rootsWithDifferentParentsAreRejectedBeforeChangingRelativeLinkSemantics() {
        val old = temp.newFolder("old")
        val newParent = temp.newFolder("other-parent")
        val new = File(newParent, "new")
        write(old, "settings", "original")
        val result = migrate(old, new)
        assertFalse(result.completed)
        assertEquals("ROOT_PARENT_MISMATCH", result.failureCode)
        assertEquals(old.absolutePath, result.physicalRoot)
        assertEquals("original", File(old, "settings").readText())
        assertFalse(new.exists())
    }

    private fun migrate(
        old: File,
        new: File,
        fs: ConfigMigrationFileSystem = local
    ): ConfigMigrationResult = ConfigMigration(fs).migrate(old.absolutePath, new.absolutePath)

    private fun write(root: File, relative: String, content: String): File = File(root, relative).apply {
        parentFile!!.mkdirs()
        writeText(content)
    }

    private fun backupContents(result: ConfigMigrationResult): List<String> =
        File(result.backupDirectory!!).listFiles()!!.flatMap { backup ->
            val data = File(backup, "data")
            data.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .map { it.readText() }.toList()
        }

    private fun resultArchive(old: File): String =
        old.parentFile!!.listFiles()!!.first { it.name.startsWith(".aicove-legacy-") && it.isDirectory }.absolutePath
}
