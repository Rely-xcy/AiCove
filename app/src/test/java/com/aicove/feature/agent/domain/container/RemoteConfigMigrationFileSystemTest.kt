package com.aicove.feature.agent.domain.container

import com.aicove.core.branding.ConfigMigration
import com.aicove.core.branding.ConfigMigrationStatus
import com.aicove.core.branding.ConfigSymlinkUnsupportedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Paths
import java.security.MessageDigest
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigMigrationFileSystemTest {
    @Test
    fun lstatReturnsNullOnlyForMissingEntries() {
        val remote = Fixture()
        val fs = RemoteConfigMigrationFileSystem(remote.client)
        assertEquals(null, fs.lstat("/home/test/missing"))
        every { remote.client.lstat("/home/test/denied") } throws SFTPException(StatusCode.PERMISSION_DENIED, "denied")
        every { remote.client.lstat("/home/test/offline") } throws IOException("Disconnected")
        assertThrows(SFTPException::class.java) { fs.lstat("/home/test/denied") }
        assertThrows(IOException::class.java) { fs.lstat("/home/test/offline") }
    }

    @Test
    fun onlyExplicitUnsupportedStatusEnablesCopyCompatibility() {
        val remote = Fixture()
        val fs = RemoteConfigMigrationFileSystem(remote.client)
        remote.linkFailure = SFTPException(StatusCode.OP_UNSUPPORTED, "unsupported")
        assertThrows(ConfigSymlinkUnsupportedException::class.java) { fs.symlink("/home/test/link", "/home/test/new") }
        remote.linkFailure = SFTPException(StatusCode.PERMISSION_DENIED, "denied")
        assertThrows(SFTPException::class.java) { fs.symlink("/home/test/link", "/home/test/new") }
        remote.linkFailure = IOException("Disconnected")
        assertThrows(IOException::class.java) { fs.symlink("/home/test/link", "/home/test/new") }
        assertFalse(remote.entries.containsKey("/home/test/link"))
    }

    @Test
    fun permissionFailureDuringRemoteMigrationSelectsOldDataInsteadOfCopyCompatibility() {
        val remote = Fixture()
        remote.directory("/home/test/.aicode")
        remote.file("/home/test/.aicode/settings", "old".toByteArray())
        remote.linkFailure = SFTPException(StatusCode.PERMISSION_DENIED, "denied")
        val result = ConfigMigration(RemoteConfigMigrationFileSystem(remote.client))
            .migrate("/home/test/.aicode", "/home/test/.aicove")
        assertEquals(ConfigMigrationStatus.INCOMPLETE, result.status)
        assertEquals("/home/test/.aicode", result.physicalRoot)
        assertArrayEquals("old".toByteArray(), remote.entries.getValue("/home/test/.aicode/settings").bytes)
        assertFalse(remote.entries.containsKey("/home/test/.aicove"))
    }

    @Test
    fun preVersionThreeIsExplicitlyUnsupportedWithoutSendingSymlink() {
        val remote = Fixture()
        every { remote.client.version() } returns 2
        assertThrows(ConfigSymlinkUnsupportedException::class.java) {
            RemoteConfigMigrationFileSystem(remote.client).symlink("/home/test/link", "/home/test/new")
        }
        verify(exactly = 0) { remote.client.symlink(any(), any()) }
    }

    @Test
    fun remoteOldOnlyMigrationUsesRenameAndOldAliasWithoutReadingPluginBytes() {
        val remote = Fixture()
        val fs = RemoteConfigMigrationFileSystem(remote.client)
        val old = "/home/test/.aicode"
        val new = "/home/test/.aicove"
        val original = "original".toByteArray()
        remote.directory(old)
        remote.file("$old/plugin", original)

        val result = ConfigMigration(fs).migrate(old, new)

        assertEquals(ConfigMigrationStatus.MIGRATED, result.status)
        verify(exactly = 1) { remote.client.rename(old, new) }
        assertArrayEquals(original, remote.entries.getValue("$new/plugin").bytes)
        assertEquals(".aicove", remote.entries.getValue(old).link)
        assertEquals(new, fs.realPath(old))
        assertTrue(remote.readPaths.none { it.endsWith("/plugin") })
        assertEquals(ConfigMigrationStatus.ALREADY_MIGRATED, ConfigMigration(fs).migrate(old, new).status)
    }

    @Test
    fun remoteUnsupportedSymlinkProducesCopyCompatibilityAndDoesNotReplayOld() {
        val remote = Fixture()
        remote.directory("/home/test/.aicode")
        remote.file("/home/test/.aicode/settings", "old".toByteArray())
        remote.linkFailure = SFTPException(StatusCode.OP_UNSUPPORTED, "unsupported")
        val first = ConfigMigration(RemoteConfigMigrationFileSystem(remote.client))
            .migrate("/home/test/.aicode", "/home/test/.aicove")
        assertEquals(ConfigMigrationStatus.COPIED_COMPATIBILITY, first.status)
        remote.file("/home/test/.aicove/settings", "new edit".toByteArray())
        val second = ConfigMigration(RemoteConfigMigrationFileSystem(remote.client))
            .migrate("/home/test/.aicode", "/home/test/.aicove")
        assertEquals(ConfigMigrationStatus.COPIED_COMPATIBILITY, second.status)
        assertArrayEquals("new edit".toByteArray(), remote.entries.getValue("/home/test/.aicove/settings").bytes)
    }

    @Test
    fun copyAndHashUseBoundedStreamingWithExclusivePrivateCreationAndMetadata() {
        val remote = Fixture()
        val bytes = ByteArray(100_003) { (it % 251).toByte() }
        remote.file("/home/test/source", bytes)
        remote.entries.getValue("/home/test/source").mode = 448
        val fs = RemoteConfigMigrationFileSystem(remote.client)
        fs.copyFile("/home/test/source", "/home/test/target")
        assertArrayEquals(bytes, remote.entries.getValue("/home/test/target").bytes)
        assertEquals(448, remote.entries.getValue("/home/test/target").mode)
        assertEquals(200L, remote.entries.getValue("/home/test/target").mtime)
        assertEquals(384, remote.createdModes.single())
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(hash, fs.hashFile("/home/test/target"))
        assertTrue(remote.readLengths.all { it <= 32768 })
        assertTrue(remote.writeLengths.all { it <= 32768 })
    }

    @Test
    fun smallReadsRejectOversizeAndWritesRemainChunkBounded() {
        val remote = Fixture()
        val fs = RemoteConfigMigrationFileSystem(remote.client)
        fs.writeSmallFile("/home/test/record", ByteArray(90_000))
        assertThrows(IOException::class.java) { fs.readSmallFile("/home/test/record", 256) }
        assertArrayEquals(ByteArray(90_000), fs.readSmallFile("/home/test/record", 90_000))
        assertTrue(remote.writeLengths.all { it <= 32768 })
    }

    @Test
    fun moveNeverOverwritesAndDirectoriesStartOwnerPrivate() {
        val remote = Fixture()
        remote.file("/home/test/source", byteArrayOf(1))
        remote.file("/home/test/target", byteArrayOf(2))
        val fs = RemoteConfigMigrationFileSystem(remote.client)
        assertThrows(FileAlreadyExistsException::class.java) { fs.move("/home/test/source", "/home/test/target") }
        assertThrows(FileAlreadyExistsException::class.java) { fs.writeSmallFile("/home/test/target", byteArrayOf(3)) }
        verify(exactly = 0) { remote.client.rename(any(), any()) }
        fs.mkdir("/home/test/private")
        assertEquals(448, remote.entries.getValue("/home/test/private").mode)
        assertArrayEquals(byteArrayOf(2), remote.entries.getValue("/home/test/target").bytes)
    }

    @Test
    fun symbolicParentsAndFileLinksAreNeverFollowed() {
        val remote = Fixture()
        remote.entries["/home/test/link"] = Node(FileMode.Type.SYMLINK, link = "/outside")
        val fs = RemoteConfigMigrationFileSystem(remote.client)
        assertThrows(IOException::class.java) { fs.lstat("/home/test/link/child") }
        assertThrows(IOException::class.java) { fs.hashFile("/home/test/link") }
        assertTrue(remote.readPaths.isEmpty())
    }

    @Test
    fun activityCheckStopsStreamingInsteadOfWritingThroughAStaleConnection() {
        val remote = Fixture()
        remote.file("/home/test/source", ByteArray(100_000))
        var checks = 0
        val fs = RemoteConfigMigrationFileSystem(remote.client) {
            if (++checks > 13) throw IOException("Connection generation changed")
        }
        assertThrows(IOException::class.java) { fs.copyFile("/home/test/source", "/home/test/target") }
        assertTrue((remote.entries["/home/test/target"]?.bytes?.size ?: 0) < 100_000)
    }

    private data class Node(
        val type: FileMode.Type,
        var bytes: ByteArray = byteArrayOf(),
        val link: String? = null,
        var mode: Int = 448,
        var mtime: Long = 200
    )

    private class Fixture {
        val client = mockk<SFTPClient>(relaxed = true)
        val entries = mutableMapOf<String, Node>()
        val readPaths = mutableListOf<String>()
        val readLengths = mutableListOf<Int>()
        val writeLengths = mutableListOf<Int>()
        val createdModes = mutableListOf<Int>()
        var linkFailure: Exception? = null

        init {
            listOf("/", "/home", "/home/test").forEach { directory(it) }
            every { client.version() } returns 3
            every { client.lstat(any()) } answers {
                attributes(entries[firstArg<String>()] ?: throw SFTPException(StatusCode.NO_SUCH_FILE, "missing"))
            }
            every { client.canonicalize(any()) } answers { resolve(firstArg()) }
            every { client.ls(any<String>()) } answers {
                val parent = firstArg<String>().trimEnd('/') + "/"
                entries.keys.filter { it.startsWith(parent) && '/' !in it.removePrefix(parent) }
                    .map { path -> mockk<RemoteResourceInfo>().also { info ->
                        every { info.name } returns path.substringAfterLast('/')
                    } }
            }
            every { client.getSFTPEngine().makeDir(any(), any()) } answers {
                val path = firstArg<String>()
                check(path !in entries)
                directory(path)
                entries.getValue(path).mode = secondArg<FileAttributes>().mode.permissionsMask
                Unit
            }
            every { client.symlink(any(), any()) } answers {
                linkFailure?.let { throw it }
                val path = firstArg<String>()
                check(path !in entries)
                entries[path] = Node(FileMode.Type.SYMLINK, link = secondArg())
                Unit
            }
            every { client.readlink(any()) } answers { entries.getValue(firstArg()).link!! }
            every { client.rename(any(), any()) } answers {
                val source = firstArg<String>()
                val target = secondArg<String>()
                check(target !in entries)
                entries.filterKeys { it == source || it.startsWith("$source/") }.toMap().forEach { (path, node) ->
                    entries.remove(path)
                    entries[target + path.removePrefix(source)] = node
                }
                Unit
            }
            every { client.chmod(any(), any()) } answers {
                entries.getValue(firstArg()).mode = secondArg()
                Unit
            }
            every { client.open(any<String>(), any<Set<OpenMode>>()) } answers { handle(firstArg()) }
            every { client.open(any<String>(), any<Set<OpenMode>>(), any<FileAttributes>()) } answers {
                val path = firstArg<String>()
                val modes = secondArg<Set<OpenMode>>()
                check(OpenMode.CREAT in modes && OpenMode.EXCL in modes && path !in entries)
                val mode = thirdArg<FileAttributes>().mode.permissionsMask
                createdModes += mode
                file(path, byteArrayOf())
                entries.getValue(path).mode = mode
                handle(path)
            }
        }

        fun directory(path: String) { entries[path] = Node(FileMode.Type.DIRECTORY) }
        fun file(path: String, bytes: ByteArray) { entries[path] = Node(FileMode.Type.REGULAR, bytes) }

        private fun attributes(node: Node): FileAttributes = FileAttributes.Builder().withType(node.type)
            .withSize(node.bytes.size.toLong()).withPermissions(node.mode).withAtimeMtime(100, node.mtime).build()

        private fun handle(path: String): RemoteFile {
            val file = mockk<RemoteFile>(relaxed = true)
            every { file.read(any(), any(), any(), any()) } answers {
                readPaths += path
                val offset = firstArg<Long>().toInt()
                val buffer = secondArg<ByteArray>()
                val length = arg<Int>(3)
                readLengths += length
                val source = entries.getValue(path).bytes
                if (offset >= source.size) -1 else {
                    val count = minOf(length, source.size - offset)
                    source.copyInto(buffer, thirdArg(), offset, offset + count)
                    count
                }
            }
            every { file.write(any(), any(), any(), any()) } answers {
                val offset = firstArg<Long>().toInt()
                val length = arg<Int>(3)
                writeLengths += length
                val node = entries.getValue(path)
                node.bytes = node.bytes.copyOf(offset + length)
                secondArg<ByteArray>().copyInto(node.bytes, offset, thirdArg(), thirdArg<Int>() + length)
                Unit
            }
            every { file.setAttributes(any()) } answers {
                val attrs = firstArg<FileAttributes>()
                entries.getValue(path).mode = attrs.mode.permissionsMask
                entries.getValue(path).mtime = attrs.mtime
                Unit
            }
            return file
        }

        private fun resolve(path: String, hops: Int = 0): String {
            check(hops < 40)
            val normalized = Paths.get(path).normalize().toString()
            var current = ""
            val parts = normalized.split('/').filter { it.isNotEmpty() }
            parts.forEachIndexed { index, part ->
                current += "/$part"
                val link = entries[current]?.link
                if (link != null) {
                    val target = if (link.startsWith('/')) link else current.substringBeforeLast('/') + "/$link"
                    val remainder = parts.drop(index + 1).joinToString("/")
                    return resolve(target + if (remainder.isEmpty()) "" else "/$remainder", hops + 1)
                }
            }
            return normalized
        }
    }
}
