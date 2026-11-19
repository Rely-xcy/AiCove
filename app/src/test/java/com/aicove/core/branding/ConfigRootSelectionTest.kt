package com.aicove.core.branding

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigRootSelectionTest {
    @get:Rule val temp = TemporaryFolder()
    private val local = LocalConfigMigrationFileSystem()

    @Test
    fun hostDirectoryReadsOldDataBeforeAnyRepositoryStarts() {
        val filesDir = temp.newFolder("files")
        val old = File(filesDir, BrandPaths.Legacy.HOST_DIR_NAME).apply { mkdir() }
        File(old, "settings").writeText("old settings")
        val result = BrandPaths.prepareHostDirectory(filesDir)
        val root = BrandPaths.hostDirectory(filesDir)
        assertTrue(result.completed)
        assertEquals(File(filesDir, BrandPaths.HOST_DIR_NAME), root)
        assertEquals("old settings", File(root, "settings").readText())
        File(old, "write-through-old").writeText("shared")
        assertEquals("shared", File(root, "write-through-old").readText())
    }

    @Test
    fun failedMergeFreezesTheOldPhysicalRootForEveryConsumerAndRetriesNextSession() {
        val old = temp.newFolder("old")
        val new = temp.newFolder("new")
        File(old, "settings").writeText("old")
        File(new, "settings").writeText("new")
        var copies = 0
        val fs = object : ConfigMigrationFileSystem by local {
            override fun copyFile(source: String, target: String) {
                copies++
                throw IOException("Copy failed")
            }
        }
        val gate = ConfigRootSelection(fs)
        val first = gate.select(old.path, new.path)
        assertFalse(first.completed)
        assertEquals(old.canonicalPath, first.physicalRoot)
        val previousCopies = copies
        assertEquals(first, gate.select(old.path, new.path))
        assertEquals(previousCopies, copies)
        File(first.physicalRoot, "settings").writeText("old updated during fallback")
        val retry = ConfigRootSelection(local).select(old.path, new.path)
        assertTrue(retry.completed)
        assertEquals("old updated during fallback", File(retry.physicalRoot, "settings").readText())
    }

    @Test
    fun completedSelectionDoesNotReplayOldDataOrRescanRoot() {
        val old = temp.newFolder("old")
        val new = File(temp.root, "new")
        File(old, "settings").writeText("old")
        var scans = 0
        val fs = object : ConfigMigrationFileSystem by local {
            override fun list(path: String): List<String> {
                scans++
                return local.list(path)
            }
            override fun hashFile(path: String): String = throw AssertionError("Unexpected tree hash")
        }
        val gate = ConfigRootSelection(fs)
        val result = gate.select(old.path, new.path)
        File(result.physicalRoot, "settings").writeText("new edit")
        val initialScans = scans
        assertEquals(result, gate.select(old.path, new.path))
        assertEquals(initialScans, scans)
        assertEquals(ConfigMigrationStatus.ALREADY_MIGRATED,
            ConfigRootSelection(fs).select(old.path, new.path).status)
        assertEquals("new edit", File(new, "settings").readText())
    }

    @Test
    fun crashProcessSelectionNeverRunsMigrationEvenWhenHostGetterIsUsedLater() {
        val filesDir = temp.newFolder("crash-files")
        val old = File(filesDir, BrandPaths.Legacy.HOST_DIR_NAME).apply { mkdir() }
        File(old, "settings").writeText("old")
        val result = BrandPaths.prepareHostDirectory(filesDir, migrate = false)
        assertEquals("MIGRATION_SKIPPED", result.failureCode)
        assertEquals(old, BrandPaths.hostDirectory(filesDir))
        assertFalse(Files.isSymbolicLink(old.toPath()))
        assertFalse(File(filesDir, BrandPaths.HOST_DIR_NAME).exists())
        assertEquals(listOf(BrandPaths.Legacy.HOST_DIR_NAME), filesDir.list()!!.toList())
    }

    @Test
    fun invalidRootIsNotTreatedAsAnEmptyInstallation() {
        val old = File(temp.root, "old").apply { writeText("not a directory") }
        val new = File(temp.root, "new")
        assertThrows(IOException::class.java) { ConfigRootSelection(local).select(old.path, new.path) }
        assertEquals("not a directory", old.readText())
        assertFalse(new.exists())
    }

    @Test
    fun sourceAndDestinationCycleFailsWithoutPublishingAnEmptyRoot() {
        val old = File(temp.root, "old")
        val new = File(temp.root, "new")
        Files.createSymbolicLink(old.toPath(), new.toPath())
        Files.createSymbolicLink(new.toPath(), old.toPath())
        assertThrows(IOException::class.java) { ConfigRootSelection(local).select(old.path, new.path) }
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertTrue(Files.isSymbolicLink(new.toPath()))
    }

    @Test
    fun brokenNewRootWithoutOldDataIsNotReplacedByAnEmptyConfiguration() {
        val old = File(temp.root, "old")
        val new = File(temp.root, "new")
        Files.createSymbolicLink(new.toPath(), File("new").toPath())
        assertThrows(IOException::class.java) { ConfigRootSelection(local).select(old.path, new.path) }
        assertTrue(Files.isSymbolicLink(new.toPath()))
        assertFalse(old.exists())
    }

    @Test
    fun failedSourceRootLinkDoesNotPublishAnUnvalidatedExternalDirectory() {
        val outside = temp.newFolder("outside")
        File(outside, "settings").writeText("external data")
        val old = File(temp.root, "old")
        val new = File(temp.root, "new")
        Files.createSymbolicLink(old.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { ConfigRootSelection(local).select(old.path, new.path) }
        assertEquals("external data", File(outside, "settings").readText())
        assertFalse(new.exists())
    }

    @Test
    fun everyProjectUsesItsOwnMigratedRootAndOldAlias() {
        val projects = listOf(temp.newFolder("project-one"), temp.newFolder("project-two"))
        projects.forEachIndexed { index, project ->
            val old = File(project, BrandPaths.Legacy.CONFIG_DIR_NAME).apply { mkdir() }
            File(old, "settings").writeText("project $index")
            val root = BrandPaths.projectDirectory(project.path)
            assertEquals(File(project, BrandPaths.CONFIG_DIR_NAME), root)
            assertEquals("project $index", File(root, "settings").readText())
            assertEquals(root.canonicalPath, old.canonicalPath)
        }
    }

    @Test
    fun unsupportedSymlinkSelectionKeepsCopyCompatibilityFrozenAcrossRestarts() {
        val old = temp.newFolder("old")
        val new = File(temp.root, "new")
        File(old, "settings").writeText("old")
        val fs = object : ConfigMigrationFileSystem by local {
            override fun symlink(link: String, target: String) { throw ConfigSymlinkUnsupportedException() }
        }
        val first = ConfigRootSelection(fs).select(old.path, new.path)
        assertEquals(ConfigMigrationStatus.COPIED_COMPATIBILITY, first.status)
        File(new, "settings").writeText("new edit")
        val restart = ConfigRootSelection(fs).select(old.path, new.path)
        assertEquals(first.physicalRoot, restart.physicalRoot)
        assertEquals("new edit", File(new, "settings").readText())
        assertEquals("old", File(old, "settings").readText())
    }
}
