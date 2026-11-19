package com.aicove.feature.plugin.data.index

import android.content.Context
import com.aicove.feature.plugin.data.PluginStoreSettingsRepository
import com.aicove.feature.plugin.domain.LocalizedText
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginIndexClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun directPackage_resolvesWithoutReleaseRequest() = runBlocking {
        val client = client { throw AssertionError("Direct package must not query Releases") }
        val asset = client.resolveRelease(plugin(version = "1.2.0", packageUrl = PACKAGE_URL))

        assertEquals("1.2.0", asset.version)
        assertEquals("community.demo-kit_1.2.0.zip", asset.name)
        assertEquals(PACKAGE_URL, asset.url)
        assertNull(asset.size)
    }

    @Test
    fun directPackage_withoutVersion_failsBeforeNetworkRequest() {
        val client = client { throw AssertionError("Invalid package must not query Releases") }
        assertThrows(PluginIndexException::class.java) {
            runBlocking { client.resolveRelease(plugin(packageUrl = PACKAGE_URL)) }
        }
    }

    @Test
    fun releaseWithoutVersion_usesLatestAndHighestMatchingAsset() = runBlocking {
        val requests = mutableListOf<String>()
        val client = client { request ->
            requests += request.url.encodedPath
            """{"assets":[
                {"name":"community.demo-kit_1.0.0.zip","browser_download_url":"$PACKAGE_URL","size":4},
                {"name":"community.demo-kit_1.2.0.zip","browser_download_url":"$PACKAGE_URL","size":6},
                {"name":"other.plugin_9.0.0.zip","browser_download_url":"$PACKAGE_URL","size":8}
            ]}""".toResponseBody()
        }

        val asset = client.resolveRelease(plugin())

        assertEquals(listOf("/repos/community/plugins/releases/latest"), requests)
        assertEquals("1.2.0", asset.version)
        assertEquals(6L, asset.size)
    }

    @Test
    fun releaseWithVersion_matchesExactAssetAndSkipsDrafts() = runBlocking {
        val client = client { request ->
            assertEquals("/repos/community/plugins/releases", request.url.encodedPath)
            assertEquals("100", request.url.queryParameter("per_page"))
            """[
                {"draft":true,"assets":[
                    {"name":"community.demo-kit_1.2.0.zip","browser_download_url":"$PACKAGE_URL","size":99}
                ]},
                {"assets":[
                    {"name":"community.demo-kit_1.3.0.zip","browser_download_url":"$PACKAGE_URL","size":8},
                    {"name":"community.demo-kit_1.2.0.zip","browser_download_url":"$PACKAGE_URL","size":6}
                ]}
            ]""".toResponseBody()
        }

        val asset = client.resolveRelease(plugin(version = "1.2.0"))

        assertEquals("1.2.0", asset.version)
        assertEquals(6L, asset.size)
    }

    @Test
    fun downloadUnknownSize_acceptsNonEmptyBodyWithoutContentLength() = runBlocking {
        val bytes = byteArrayOf(0x50, 0x4b, 3, 4)
        val client = client { bytes.toResponseBody().source().asResponseBody() }
        val downloaded = client.download(asset(size = null))

        assertTrue(downloaded.file.isFile)
        assertArrayEquals(bytes, downloaded.file.readBytes())
    }

    @Test
    fun downloadUnknownSize_acceptsExactArchiveLimit() = runBlocking {
        val client = client { ByteArray(MAX_ARCHIVE_BYTES).toResponseBody().source().asResponseBody() }

        val downloaded = client.download(asset(size = null))

        assertEquals(MAX_ARCHIVE_BYTES.toLong(), downloaded.file.length())
    }

    @Test
    fun downloadUnknownSize_rejectsEmptyBody() {
        val client = client { ByteArray(0).toResponseBody().source().asResponseBody() }
        assertThrows(PluginIndexException::class.java) {
            runBlocking { client.download(asset(size = null)) }
        }
        assertNoDownloadedFiles()
    }

    @Test
    fun downloadUnknownSize_rejectsOversizedDeclaredLength() {
        val client = client {
            ByteArray(0).toResponseBody().source().asResponseBody(contentLength = MAX_ARCHIVE_BYTES + 1L)
        }
        assertThrows(IOException::class.java) {
            runBlocking { client.download(asset(size = null)) }
        }
        assertNoDownloadedFiles()
    }

    @Test
    fun downloadUnknownSize_rejectsOversizedStreamWithoutContentLength() {
        val client = client { ByteArray(MAX_ARCHIVE_BYTES + 1).toResponseBody().source().asResponseBody() }
        assertThrows(IOException::class.java) {
            runBlocking { client.download(asset(size = null)) }
        }
        assertNoDownloadedFiles()
    }

    @Test
    fun downloadRelease_requiresPositiveBoundedSizeBeforeRequest() {
        val client = client { throw AssertionError("Invalid size must fail before downloading") }
        listOf(-1L, 0L, MAX_ARCHIVE_BYTES + 1L).forEach { size ->
            assertThrows(PluginIndexException::class.java) {
                runBlocking { client.download(asset(size)) }
            }
        }
        assertNoDownloadedFiles()
    }

    @Test
    fun downloadRelease_rejectsMismatchingDeclaredLength() {
        val client = client { ByteArray(3).toResponseBody() }
        assertThrows(IOException::class.java) {
            runBlocking { client.download(asset(size = 4)) }
        }
        assertNoDownloadedFiles()
    }

    @Test
    fun downloadRelease_rejectsTruncatedAndOversizedStreams() {
        listOf(3, 5).forEach { actualSize ->
            val client = client { ByteArray(actualSize).toResponseBody().source().asResponseBody() }
            assertThrows(IOException::class.java) {
                runBlocking { client.download(asset(size = 4)) }
            }
        }
        assertNoDownloadedFiles()
    }

    @Test
    fun downloadRelease_acceptsExactSize() = runBlocking {
        val bytes = byteArrayOf(0x50, 0x4b, 3, 4)
        val client = client { bytes.toResponseBody() }

        val downloaded = client.download(asset(size = 4))

        assertArrayEquals(bytes, downloaded.file.readBytes())
        assertEquals(4L, downloaded.asset.size)
    }

    private fun client(respond: (Request) -> ResponseBody): PluginIndexClient {
        val context = mockk<Context>()
        every { context.filesDir } returns temp.root
        every { context.cacheDir } returns temp.root
        every { context.packageManager } throws UnsupportedOperationException("No host version in unit test")
        val settings = mockk<PluginStoreSettingsRepository>()
        every { settings.mirrorCandidates } returns flowOf(listOf(""))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(respond(chain.request()))
                .build()
        }.build()
        return PluginIndexClient(context, http, settings)
    }

    private fun plugin(version: String? = null, packageUrl: String? = null) = PluginIndexPlugin(
        id = "community.demo-kit",
        name = LocalizedText.single("Demo Kit"),
        description = LocalizedText.single("Demo plugin"),
        author = "community",
        repository = "https://github.com/community/plugins",
        version = version,
        packageUrl = packageUrl
    )

    private fun asset(size: Long?) = PluginReleaseAsset("1.2.0", "community.demo-kit_1.2.0.zip", PACKAGE_URL, size)

    private fun assertNoDownloadedFiles() {
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }

    private companion object {
        const val PACKAGE_URL = "https://raw.githubusercontent.com/xzyy03/Muse-Plugins-Store/main/packages/community.demo-kit_1.2.0.zip"
        const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
    }
}
