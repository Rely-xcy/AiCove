package com.aicove.feature.agent.domain.container

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException

class BuiltinDocsSyncTest {
    @Test
    fun unchanged_readsOnlyStateAndDoesNotWriteOrStatDocuments() = runBlocking<Unit> {
        val backend = FakeBackend()
        val snapshot = BuiltinDocsSync.snapshot(mapOf("guide/start.md" to "hello", "advanced/ssh.md" to "ssh"))
        BuiltinDocsSync(backend).sync(snapshot)
        backend.resetOperations()

        val result = BuiltinDocsSync(backend).sync(snapshot)

        assertEquals(BuiltinDocsSync.Result(false, 0, 0), result)
        assertEquals(listOf(STATE), backend.reads)
        assertEquals(listOf("", STATE), backend.stats)
        assertTrue(backend.hashReads.isEmpty())
        assertTrue(backend.mutations.isEmpty())
    }

    @Test
    fun changed_uploadsOnlyChangedAndNewDocumentsAndMarksDirtyFirst() = runBlocking<Unit> {
        val backend = FakeBackend()
        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "A", "b.md" to "B")))
        backend.resetOperations()

        val result = BuiltinDocsSync(backend).sync(
            BuiltinDocsSync.snapshot(mapOf("a.md" to "new A", "b.md" to "B", "c.md" to "C"))
        )

        assertEquals(BuiltinDocsSync.Result(true, 2, 0), result)
        assertEquals(listOf(STATE, "a.md", "c.md", STATE), backend.writes.map { it.first })
        assertTrue(backend.writes.first().second.startsWith("aicode-docs-v1\tdirty\t"))
        assertTrue(backend.writes.last().second.startsWith("aicode-docs-v1\tready\t"))
        assertEquals("new A", backend.text("a.md"))
        assertEquals("B", backend.text("b.md"))
    }

    @Test
    fun removed_deletesOnlyPreviouslyManagedFiles() = runBlocking<Unit> {
        val backend = FakeBackend()
        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("old.md" to "old", "keep.md" to "keep")))
        backend.putFile("user.md", "private")
        backend.resetOperations()

        val result = BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("keep.md" to "keep")))

        assertEquals(BuiltinDocsSync.Result(true, 0, 1), result)
        assertEquals(listOf("old.md"), backend.deletes)
        assertEquals("private", backend.text("user.md"))
        assertFalse(backend.entries.containsKey("old.md"))
    }

    @Test
    fun firstSync_preservesUnknownFilesAndBuildsNestedDirectories() = runBlocking<Unit> {
        val backend = FakeBackend()
        backend.putFile("user.md", "custom")
        backend.resetOperations()

        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("guide/deep/start.md" to "hello")))

        assertTrue(backend.deletes.isEmpty())
        assertEquals("custom", backend.text("user.md"))
        assertEquals("hello", backend.text("guide/deep/start.md"))
        assertEquals(BuiltinDocsSync.EntryType.DIRECTORY, backend.entries["guide/deep"]?.type)
        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tready\t"))
    }

    @Test
    fun firstSync_createsMissingRootBeforeStateAndDocuments() = runBlocking<Unit> {
        val backend = FakeBackend()
        backend.entries.remove("")

        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "A")))

        assertEquals(listOf("mkdir:", "write:$STATE", "write:a.md", "write:$STATE"), backend.mutations)
    }

    @Test
    fun firstSync_backsUpConflictingExistingFileBeforeUpdatingIt() = runBlocking<Unit> {
        val backend = FakeBackend()
        backend.putFile("guide/start.md", "user content")
        backend.entries["guide"] = Node(BuiltinDocsSync.EntryType.DIRECTORY)
        val snapshot = BuiltinDocsSync.snapshot(mapOf("guide/start.md" to "builtin"))
        val hash = BuiltinDocsSync.sha256("user content".toByteArray())
        val backup = "${BuiltinDocsSync.BACKUP_DIR}/$hash/guide/start.md"

        BuiltinDocsSync(backend).sync(snapshot)

        assertEquals("builtin", backend.text("guide/start.md"))
        assertEquals("user content", backend.text(backup))
        assertTrue(backend.mutations.indexOf("backup:guide/start.md") < backend.mutations.indexOf("write:guide/start.md"))
        backend.resetOperations()
        BuiltinDocsSync(backend).sync(snapshot)
        assertTrue(backend.backups.isEmpty())
        assertTrue(backend.mutations.isEmpty())
    }

    @Test
    fun backupFailure_preservesOriginalFileAndLeavesDirty() = runBlocking<Unit> {
        val backend = FakeBackend()
        backend.putFile("a.md", "user content")
        backend.failBackup = true

        assertThrows(IOException::class.java) {
            runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "builtin"))) }
        }

        assertEquals("user content", backend.text("a.md"))
        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tdirty\t"))
    }

    @Test
    fun interruptedBeforeNewPathUpload_doesNotDeleteConflictingUserFileOnRollback() = runBlocking<Unit> {
        val backend = FakeBackend()
        val a = BuiltinDocsSync.snapshot(mapOf("old.md" to "A"))
        BuiltinDocsSync(backend).sync(a)
        backend.putFile("b.md", "user content")
        backend.failWrite = "a-failure.md"

        assertThrows(IOException::class.java) {
            runBlocking {
                BuiltinDocsSync(backend).sync(
                    BuiltinDocsSync.snapshot(mapOf("a-failure.md" to "fail", "b.md" to "builtin B"))
                )
            }
        }
        backend.failWrite = null
        backend.resetOperations()

        BuiltinDocsSync(backend).sync(a)

        assertEquals("user content", backend.text("b.md"))
        assertTrue(backend.deletes.isEmpty())
        assertTrue(backend.backups.isEmpty())
    }

    @Test
    fun removedUserModifiedManagedFile_isPreservedAndNoLongerManaged() = runBlocking<Unit> {
        val backend = FakeBackend()
        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("old.md" to "builtin")))
        backend.putFile("old.md", "user edited")
        backend.resetOperations()

        val result = BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(emptyMap()))

        assertEquals(0, result.deleted)
        assertEquals("user edited", backend.text("old.md"))
        assertTrue(backend.deletes.isEmpty())
        assertFalse(backend.text(STATE).contains("old.md"))
    }

    @Test
    fun interruptedReplacement_removedPathRecognizesBothOldAndNewAppHashes() = runBlocking<Unit> {
        listOf("A", "B").forEach { actual ->
            val backend = FakeBackend()
            BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "A")))
            backend.failWrite = "z.md"
            assertThrows(IOException::class.java) {
                runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "B", "z.md" to "fail"))) }
            }
            backend.putFile("a.md", actual)
            backend.failWrite = null

            BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(emptyMap()))

            assertFalse(backend.entries.containsKey("a.md"))
        }
    }

    @Test
    fun leftoverStateAndDocumentTemporaryFiles_areReclaimedOnRetry() = runBlocking<Unit> {
        val backend = FakeBackend()
        val a = BuiltinDocsSync.snapshot(mapOf("a.md" to "A"))
        BuiltinDocsSync(backend).sync(a)
        backend.temporaryFiles += BuiltinDocsSync.temporaryPath(STATE)

        assertFalse(BuiltinDocsSync(backend).sync(a).changed)
        assertTrue(backend.temporaryFiles.isEmpty())

        backend.failWrite = "z.md"
        assertThrows(IOException::class.java) {
            runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("b.md" to "B", "z.md" to "fail"))) }
        }
        backend.temporaryFiles += BuiltinDocsSync.temporaryPath("z.md")
        backend.failWrite = null

        BuiltinDocsSync(backend).sync(a)

        assertTrue(backend.temporaryFiles.isEmpty())
        assertFalse(backend.entries.containsKey("b.md"))
    }

    @Test
    fun fingerprint_includesPathAndIsIndependentOfInputOrder() {
        val first = BuiltinDocsSync.snapshot(linkedMapOf("a.md" to "same", "b.md" to "other"))
        val reordered = BuiltinDocsSync.snapshot(linkedMapOf("b.md" to "other", "a.md" to "same"))
        val renamed = BuiltinDocsSync.snapshot(mapOf("renamed.md" to "same", "b.md" to "other"))

        assertEquals(64, first.fingerprint.length)
        assertEquals(first.fingerprint, reordered.fingerprint)
        assertNotEquals(first.fingerprint, renamed.fingerprint)
    }

    @Test
    fun rename_uploadsNewPathAndDeletesOldPathEvenWithIdenticalContent() = runBlocking<Unit> {
        val backend = FakeBackend()
        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("old.md" to "same")))
        backend.resetOperations()

        val result = BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("guide/new.md" to "same")))

        assertEquals(BuiltinDocsSync.Result(true, 1, 1), result)
        assertEquals(listOf("old.md"), backend.deletes)
        assertEquals("same", backend.text("guide/new.md"))
    }

    @Test
    fun interruptedUpdate_canReturnToPreviousSnapshotAndRemovePartialAdditions() = runBlocking<Unit> {
        val backend = FakeBackend()
        val a = BuiltinDocsSync.snapshot(mapOf("stable.md" to "A", "removed.md" to "original"))
        val b = BuiltinDocsSync.snapshot(mapOf("added.md" to "new", "stable.md" to "B", "z.md" to "fail"))
        BuiltinDocsSync(backend).sync(a)
        backend.failWrite = "z.md"

        assertThrows(IOException::class.java) { runBlocking { BuiltinDocsSync(backend).sync(b) } }
        assertEquals("new", backend.text("added.md"))
        assertEquals("B", backend.text("stable.md"))
        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tdirty\t"))
        backend.failWrite = null
        backend.resetOperations()

        val recovered = BuiltinDocsSync(backend).sync(a)

        assertTrue(recovered.changed)
        assertEquals("A", backend.text("stable.md"))
        assertEquals("original", backend.text("removed.md"))
        assertFalse(backend.entries.containsKey("added.md"))
        assertTrue(backend.deletes.contains("added.md"))
        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tready\t${a.fingerprint}"))
    }

    @Test
    fun repeatedInterruptions_preserveAllPendingPathsForLaterCleanup() = runBlocking<Unit> {
        val backend = FakeBackend()
        val a = BuiltinDocsSync.snapshot(mapOf("a.md" to "A"))
        BuiltinDocsSync(backend).sync(a)
        backend.failWrite = "z.md"
        assertThrows(IOException::class.java) {
            runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("b.md" to "B", "z.md" to "Z"))) }
        }
        assertThrows(IOException::class.java) {
            runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("c.md" to "C", "z.md" to "Z"))) }
        }
        backend.failWrite = null

        BuiltinDocsSync(backend).sync(a)

        assertEquals(setOf("", STATE, "a.md"), backend.entries.keys)
        assertTrue(backend.deletes.containsAll(listOf("b.md", "c.md")))
    }

    @Test
    fun dirtyWriteFailure_doesNotChangeDocumentsOrLosePreviousReady() = runBlocking<Unit> {
        val backend = FakeBackend()
        val a = BuiltinDocsSync.snapshot(mapOf("a.md" to "A"))
        BuiltinDocsSync(backend).sync(a)
        backend.failWrite = STATE
        backend.resetOperations()

        assertThrows(IOException::class.java) {
            runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "B"))) }
        }

        assertEquals("A", backend.text("a.md"))
        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tready\t${a.fingerprint}"))
        assertTrue(backend.mutations.isEmpty())
    }

    @Test
    fun documentReplacementFailure_preservesOldContentsAndLeavesDirty() = runBlocking<Unit> {
        val backend = FakeBackend()
        val a = BuiltinDocsSync.snapshot(mapOf("a.md" to "A"))
        BuiltinDocsSync(backend).sync(a)
        backend.failWrite = "a.md"

        assertThrows(IOException::class.java) {
            runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "B"))) }
        }

        assertEquals("A", backend.text("a.md"))
        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tdirty\t"))
        backend.failWrite = null
        assertTrue(BuiltinDocsSync(backend).sync(a).changed)
    }

    @Test
    fun readyCommitFailure_leavesDirtyAndRetryRepairsIt() = runBlocking<Unit> {
        val backend = FakeBackend()
        val snapshot = BuiltinDocsSync.snapshot(mapOf("a.md" to "A"))
        backend.failReady = true

        assertThrows(IOException::class.java) { runBlocking { BuiltinDocsSync(backend).sync(snapshot) } }
        assertEquals("A", backend.text("a.md"))
        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tdirty\t"))
        backend.failReady = false

        assertTrue(BuiltinDocsSync(backend).sync(snapshot).changed)
        assertFalse(BuiltinDocsSync(backend).sync(snapshot).changed)
    }

    @Test
    fun deleteFailure_leavesDirtyAndCanBeRetried() = runBlocking<Unit> {
        val backend = FakeBackend()
        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("old.md" to "old")))
        val next = BuiltinDocsSync.snapshot(mapOf("new.md" to "new"))
        backend.failDelete = "old.md"

        assertThrows(IOException::class.java) { runBlocking { BuiltinDocsSync(backend).sync(next) } }
        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tdirty\t"))
        backend.failDelete = null

        BuiltinDocsSync(backend).sync(next)
        assertFalse(backend.entries.containsKey("old.md"))
        assertEquals("new", backend.text("new.md"))
    }

    @Test
    fun emptySnapshot_removesOnlyManagedFiles() = runBlocking<Unit> {
        val backend = FakeBackend()
        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("old.md" to "old")))
        backend.putFile("user.md", "custom")

        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(emptyMap()))

        assertFalse(backend.entries.containsKey("old.md"))
        assertEquals("custom", backend.text("user.md"))
        assertFalse(BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(emptyMap())).changed)
    }

    @Test
    fun unsafeLocalPaths_areRejectedBeforeAnyBackendUse() {
        listOf("../x.md", "/x.md", "guide/../../x.md", "guide//x.md", "./x.md", "guide/./x.md",
            "C:/x.md", "guide\\x.md", "x\n.md", "x\t.md", "x\u0000.md", "not-markdown.txt", "",
            "${BuiltinDocsSync.BACKUP_DIR}/x.md", "${BuiltinDocsSync.STAGING_DIR}/x.md"
        ).forEach { path ->
            assertThrows("path=$path", IllegalArgumentException::class.java) {
                BuiltinDocsSync.snapshot(mapOf(path to "bad"))
            }
        }
    }

    @Test
    fun unsafeManifestPaths_areRejectedWithoutMutation() = runBlocking<Unit> {
        listOf("../outside.md", "/outside.md", "guide/../../outside.md", "guide\\outside.md").forEach { path ->
            val backend = FakeBackend()
            backend.putFile(STATE, "aicode-docs-v1\tdirty\t${"0".repeat(64)}\n${"0".repeat(64)}\t$path\n")
            backend.resetOperations()

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("ok.md" to "ok"))) }
            }

            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test
    fun invalidManifestAndFingerprint_areRejectedWithoutMutation() = runBlocking<Unit> {
        val backend = FakeBackend()
        val snapshot = BuiltinDocsSync.snapshot(mapOf("a.md" to "A"))
        BuiltinDocsSync(backend).sync(snapshot)
        val valid = backend.text(STATE)
        listOf("partial", valid.replace(snapshot.fingerprint, "0".repeat(64)), valid + "bad\n").forEach { state ->
            backend.putFile(STATE, state)
            backend.resetOperations()

            assertThrows(IllegalArgumentException::class.java) { runBlocking { BuiltinDocsSync(backend).sync(snapshot) } }

            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test
    fun symlinkedRootOrState_isRejectedWithoutMutation() = runBlocking<Unit> {
        listOf("", STATE).forEach { path ->
            val backend = FakeBackend()
            backend.entries[path] = Node(BuiltinDocsSync.EntryType.SYMLINK)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "A"))) }
            }

            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test
    fun removedFileBelowSymlink_isNeverTraversedOrDeleted() = runBlocking<Unit> {
        val backend = FakeBackend()
        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("guide/old.md" to "old")))
        backend.entries["guide"] = Node(BuiltinDocsSync.EntryType.SYMLINK)
        backend.resetOperations()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(emptyMap())) }
        }

        assertEquals("old", backend.text("guide/old.md"))
        assertFalse(backend.stats.contains("guide/old.md"))
        assertTrue(backend.deletes.isEmpty())
        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tdirty\t"))
    }

    @Test
    fun symlinkedDocument_isNotOverwrittenOrDeleted() = runBlocking<Unit> {
        val backend = FakeBackend()
        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "A")))
        backend.entries["a.md"] = Node(BuiltinDocsSync.EntryType.SYMLINK)
        backend.resetOperations()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "B"))) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(emptyMap())) }
        }

        assertEquals(BuiltinDocsSync.EntryType.SYMLINK, backend.entries["a.md"]?.type)
        assertTrue(backend.writes.all { it.first == STATE })
        assertTrue(backend.deletes.isEmpty())
    }

    @Test
    fun cancellationAfterFirstUpload_leavesRecoverableDirtyState() = runBlocking<Unit> {
        val backend = FakeBackend()
        var cancelled = false
        backend.afterWrite = { path -> if (path == "a.md") cancelled = true }
        val snapshot = BuiltinDocsSync.snapshot(mapOf("a.md" to "A", "b.md" to "B"))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                BuiltinDocsSync(backend) {
                    if (cancelled) throw CancellationException("cancelled")
                }.sync(snapshot)
            }
        }

        assertTrue(backend.text(STATE).startsWith("aicode-docs-v1\tdirty\t"))
        assertFalse(backend.entries.containsKey("b.md"))
        backend.afterWrite = {}
        BuiltinDocsSync(backend).sync(snapshot)
        assertEquals("B", backend.text("b.md"))
        assertFalse(BuiltinDocsSync(backend).sync(snapshot).changed)
    }

    @Test
    fun changedManagedDocument_backsUpUserEditsBeforeUpdating() = runBlocking<Unit> {
        val backend = FakeBackend()
        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "old")))
        backend.putFile("a.md", "user edited")
        backend.resetOperations()

        BuiltinDocsSync(backend).sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "new")))

        val backup = "${BuiltinDocsSync.BACKUP_DIR}/${BuiltinDocsSync.sha256("user edited".toByteArray())}/a.md"
        assertEquals("user edited", backend.text(backup))
        assertEquals("new", backend.text("a.md"))
        assertTrue(backend.mutations.indexOf("backup:a.md") < backend.mutations.indexOf("write:a.md"))
    }

    @Test
    fun agentStaging_isVerifiedThenPublishedByAtomicSftpRename() = runBlocking<Unit> {
        val remote = SftpFixture()
        var staged: String? = null
        val backend = RemoteDocsSftpBackend(remote.client, "/home/test", {}) { path, bytes ->
            staged = path
            remote.files[path] = bytes.copyOf()
            true
        }

        backend.replaceAtomically("a.md", "new".toByteArray())

        assertTrue(checkNotNull(staged).contains(".part.agent-"))
        assertTrue(remote.openedForWrite.isEmpty())
        assertEquals("new", remote.text(remote.destination))
        assertEquals(listOf(setOf(RenameFlags.ATOMIC, RenameFlags.OVERWRITE)), remote.renameFlags)
    }

    @Test
    fun agentFailure_usesDifferentSftpStagingEvenWhenAgentRespondsLate() = runBlocking<Unit> {
        val remote = SftpFixture()
        var agentPath: String? = null
        val backend = RemoteDocsSftpBackend(remote.client, "/home/test", {}) { path, _ ->
            agentPath = path
            remote.files[path] = "partial".toByteArray()
            false
        }
        remote.beforeRename = { remote.files[checkNotNull(agentPath)] = "late".toByteArray() }

        backend.replaceAtomically("a.md", "new".toByteArray())

        assertNotEquals(agentPath, remote.openedForWrite.single())
        assertEquals("new", remote.text(remote.destination))
        assertFalse(remote.removals.contains(remote.destination))
    }

    @Test
    fun agentStagingHashMismatch_keepsOriginalWithoutPublishing() = runBlocking<Unit> {
        val remote = SftpFixture()
        val backend = RemoteDocsSftpBackend(remote.client, "/home/test", {}) { path, _ ->
            remote.files[path] = "partial".toByteArray()
            true
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { backend.replaceAtomically("a.md", "new".toByteArray()) }
        }

        assertEquals("old", remote.text(remote.destination))
        assertTrue(remote.renameFlags.isEmpty())
    }

    @Test
    fun unsupportedAtomicRename_doesNotDeleteOrTruncateDestination() = runBlocking<Unit> {
        val remote = SftpFixture()
        remote.renameFailure = SFTPException(StatusCode.OP_UNSUPPORTED, "atomic rename unavailable")
        val backend = RemoteDocsSftpBackend(remote.client, "/home/test", {})

        assertThrows(SFTPException::class.java) {
            runBlocking { backend.replaceAtomically("a.md", "new".toByteArray()) }
        }

        assertEquals("old", remote.text(remote.destination))
        assertEquals(listOf(setOf(RenameFlags.ATOMIC, RenameFlags.OVERWRITE)), remote.renameFlags)
        assertFalse(remote.removals.contains(remote.destination))
        assertFalse(remote.openedForWrite.contains(remote.destination))
    }

    @Test
    fun connectionChangeDuringAgentWrite_preventsPublishing() = runBlocking<Unit> {
        val remote = SftpFixture()
        var active = true
        val backend = RemoteDocsSftpBackend(remote.client, "/home/test", {
            if (!active) throw CancellationException("SSH docs sync target changed")
        }) { path, bytes ->
            remote.files[path] = bytes.copyOf()
            active = false
            true
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { backend.replaceAtomically("a.md", "new".toByteArray()) }
        }

        assertEquals("old", remote.text(remote.destination))
        assertTrue(remote.renameFlags.isEmpty())
    }

    @Test
    fun symlinkedStaging_isRejectedBeforeAgentWrite() = runBlocking<Unit> {
        val remote = SftpFixture()
        remote.symlinks += "${remote.root}/${BuiltinDocsSync.STAGING_DIR}"
        var agentCalls = 0
        val backend = RemoteDocsSftpBackend(remote.client, "/home/test", {}) { _, _ ->
            agentCalls++
            true
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { backend.replaceAtomically("a.md", "new".toByteArray()) }
        }

        assertEquals(0, agentCalls)
        assertEquals("old", remote.text(remote.destination))
        assertTrue(remote.renameFlags.isEmpty())
    }

    private class SftpFixture {
        val root = "/home/test/.aicove/docs"
        val destination = "$root/a.md"
        val client = mockk<SFTPClient>(relaxed = true)
        val files = mutableMapOf(destination to "old".toByteArray())
        val symlinks = mutableSetOf<String>()
        val openedForWrite = mutableListOf<String>()
        val removals = mutableListOf<String>()
        val renameFlags = mutableListOf<Set<RenameFlags>>()
        var renameFailure: Exception? = null
        var beforeRename: () -> Unit = {}
        private val directories = mutableSetOf("/home", "/home/test", "/home/test/.aicove", root)

        init {
            every { client.lstat(any()) } answers {
                val path = firstArg<String>()
                val entryType = when {
                    path in symlinks -> FileMode.Type.SYMLINK
                    path in directories -> FileMode.Type.DIRECTORY
                    path in files -> FileMode.Type.REGULAR
                    else -> throw SFTPException(StatusCode.NO_SUCH_FILE, path)
                }
                val attributes = mockk<FileAttributes>()
                every { attributes.type } returns entryType
                every { attributes.size } returns (files[path]?.size ?: 0).toLong()
                attributes
            }
            every { client.mkdir(any()) } answers { directories += firstArg<String>(); Unit }
            every { client.ls(any<String>()) } returns emptyList()
            every { client.rm(any()) } answers {
                val path = firstArg<String>()
                removals += path
                files.remove(path)
                Unit
            }
            every { client.open(any<String>(), any<Set<OpenMode>>()) } answers {
                val path = firstArg<String>()
                val modes = secondArg<Set<OpenMode>>()
                if (OpenMode.WRITE in modes) {
                    openedForWrite += path
                    files[path] = byteArrayOf()
                }
                val file = mockk<RemoteFile>(relaxed = true)
                every { file.read(any(), any(), any(), any()) } answers {
                    val offset = firstArg<Long>().toInt()
                    val buffer = secondArg<ByteArray>()
                    val start = thirdArg<Int>()
                    val length = arg<Int>(3)
                    val source = files.getValue(path)
                    if (offset >= source.size) -1 else {
                        val count = minOf(length, source.size - offset)
                        source.copyInto(buffer, start, offset, offset + count)
                        count
                    }
                }
                every { file.write(any(), any(), any(), any()) } answers {
                    val offset = firstArg<Long>().toInt()
                    val source = secondArg<ByteArray>()
                    val start = thirdArg<Int>()
                    val length = arg<Int>(3)
                    val bytes = files.getValue(path).copyOf(offset + length)
                    source.copyInto(bytes, offset, start, start + length)
                    files[path] = bytes
                    Unit
                }
                file
            }
            every { client.rename(any(), any(), any<Set<RenameFlags>>()) } answers {
                renameFlags += thirdArg<Set<RenameFlags>>().toSet()
                renameFailure?.let { throw it }
                beforeRename()
                files[secondArg<String>()] = checkNotNull(files.remove(firstArg<String>()))
                Unit
            }
        }

        fun text(path: String): String = files.getValue(path).toString(Charsets.UTF_8)
    }

    private data class Node(val type: BuiltinDocsSync.EntryType, val bytes: ByteArray = byteArrayOf())

    private class FakeBackend : BuiltinDocsSync.Backend {
        val entries = mutableMapOf("" to Node(BuiltinDocsSync.EntryType.DIRECTORY))
        val stats = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val hashReads = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String>>()
        val backups = mutableListOf<String>()
        val temporaryFiles = mutableSetOf<String>()
        val deletes = mutableListOf<String>()
        val mutations = mutableListOf<String>()
        var failWrite: String? = null
        var failDelete: String? = null
        var failReady = false
        var failBackup = false
        var afterWrite: (String) -> Unit = {}

        override suspend fun type(path: String): BuiltinDocsSync.EntryType? {
            stats += path
            return entries[path]?.type
        }

        override suspend fun readFile(path: String): ByteArray {
            reads += path
            return entries.getValue(path).bytes.copyOf()
        }

        override suspend fun fileHash(path: String): String {
            hashReads += path
            return BuiltinDocsSync.sha256(entries.getValue(path).bytes)
        }

        override suspend fun backupFile(path: String, backupPath: String, expectedHash: String) {
            if (failBackup) throw IOException("injected backup failure")
            val bytes = entries.getValue(path).bytes
            check(BuiltinDocsSync.sha256(bytes) == expectedHash)
            entries[backupPath] = Node(BuiltinDocsSync.EntryType.FILE, bytes.copyOf())
            backups += backupPath
            mutations += "backup:$path"
        }

        override suspend fun cleanupTemporary(path: String) {
            val temporary = BuiltinDocsSync.temporaryPath(path)
            if (temporaryFiles.remove(temporary)) mutations += "cleanup:$temporary"
        }

        override suspend fun createDirectory(path: String) {
            entries[path] = Node(BuiltinDocsSync.EntryType.DIRECTORY)
            mutations += "mkdir:$path"
        }

        override suspend fun replaceAtomically(path: String, bytes: ByteArray) {
            val text = bytes.toString(Charsets.UTF_8)
            if (path == failWrite || (failReady && path == STATE && text.startsWith("aicode-docs-v1\tready\t"))) {
                throw IOException("injected write failure")
            }
            entries[path] = Node(BuiltinDocsSync.EntryType.FILE, bytes.copyOf())
            writes += path to text
            mutations += "write:$path"
            afterWrite(path)
        }

        override suspend fun deleteFile(path: String) {
            if (path == failDelete) throw IOException("injected delete failure")
            entries.remove(path)
            deletes += path
            mutations += "delete:$path"
        }

        fun putFile(path: String, text: String) {
            entries[path] = Node(BuiltinDocsSync.EntryType.FILE, text.toByteArray(Charsets.UTF_8))
        }

        fun text(path: String): String = entries.getValue(path).bytes.toString(Charsets.UTF_8)

        fun resetOperations() {
            stats.clear()
            reads.clear()
            hashReads.clear()
            writes.clear()
            backups.clear()
            deletes.clear()
            mutations.clear()
        }
    }

    private companion object {
        const val STATE = BuiltinDocsSync.STATE_FILE
    }
}
