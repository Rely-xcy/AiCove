package com.aicove.feature.plugin.data

import android.content.Context
import com.aicove.R
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginStoreUploaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = Dispatcher()
    private val requests = CopyOnWriteArrayList<Request>()
    private val context = mockk<Context> {
        every { getString(R.string.muse_plugin_publish_submitted) } returns "Submitted for review."
        every { getString(R.string.muse_plugin_publish_unconfigured) } returns "Inbox unavailable."
        every { getString(R.string.muse_plugin_publish_lookup_failed, 403) } returns "Lookup failed (HTTP 403)."
        every { getString(R.string.muse_plugin_publish_lookup_invalid) } returns "Lookup response invalid."
        every { getString(R.string.muse_plugin_publish_write_failed, 422) } returns "Upload failed (HTTP 422)."
        every { getString(R.string.muse_plugin_publish_failed) } returns "Submission failed."
        every { getString(R.string.muse_plugin_publish_unreadable) } returns "Package unreadable."
        every { getString(R.string.muse_plugin_publish_too_large, 20) } returns "Package exceeds 20 MB."
    }

    @After
    fun stopDispatcher() {
        dispatcher.executorService.shutdown()
    }

    @Test
    fun createsAfter404AndSendsBase64WithoutSha() = runBlocking {
        val uploader = uploader { chain -> respond(chain.request(), if (chain.request().method == "GET") 404 else 201) }

        assertTrue(uploader.isConfigured())
        assertEquals(PluginUploadResult(true, "Submitted for review."), uploader.upload(packageFile(), META))
        assertEquals(listOf("GET", "PUT"), requests.map { it.method })
        requests.forEach {
            assertEquals("https://api.github.com/repos/$REPO/contents/incoming/demo.weather_1.2.3.zip", it.url.toString())
            assertEquals("Bearer $SYNTHETIC_TOKEN", it.header("Authorization"))
            assertNull(it.url.query)
        }
        assertEquals("application/vnd.github.object+json", requests[0].header("Accept"))
        val body = putBody()
        assertEquals(setOf("message", "content"), body.keys)
        assertEquals("plugin: demo.weather 1.2.3", body.getValue("message").jsonPrimitive.content)
        assertArrayEquals(ZIP_BYTES, Base64.getDecoder().decode(body.getValue("content").jsonPrimitive.content))
    }

    @Test
    fun overwritesExistingFileUsingReturnedSha() = runBlocking {
        val uploader = uploader { chain ->
            val request = chain.request()
            if (request.method == "GET") respond(request, 200, """{"sha":"$SHA","encoding":"none"}""")
            else respond(request, 200)
        }

        assertEquals(PluginUploadResult(true, "Submitted for review."), uploader.upload(packageFile(), META))
        assertEquals(listOf("GET", "PUT"), requests.map { it.method })
        assertEquals(SHA, putBody().getValue("sha").jsonPrimitive.content)
    }

    @Test
    fun forbiddenLookupStopsBeforePutAndIgnoresRemoteText() = runBlocking {
        val uploader = uploader { chain -> respond(chain.request(), 403, """{"message":"$SYNTHETIC_TOKEN"}""") }

        val result = uploader.upload(packageFile(), META)

        assertEquals(listOf("GET"), requests.map { it.method })
        assertEquals(PluginUploadResult(false, "Lookup failed (HTTP 403)."), result)
        assertFalse(result.toString().contains(SYNTHETIC_TOKEN))
    }

    @Test
    fun lookupWithMissingOrMalformedShaStopsBeforePut() = runBlocking {
        val file = packageFile()
        val bodies = listOf("{}", """{"sha":null}""", """{"sha":17}""", """{"sha":"$SYNTHETIC_TOKEN"}""", "[")
        for (body in bodies) {
            val uploader = uploader { chain -> respond(chain.request(), 200, body) }

            assertEquals(PluginUploadResult(false, "Lookup response invalid."), uploader.upload(file, META))
        }
        assertEquals(List(bodies.size) { "GET" }, requests.map { it.method })
    }

    @Test
    fun putFailureReportsStatusOnly() = runBlocking {
        val uploader = uploader { chain ->
            val request = chain.request()
            if (request.method == "GET") respond(request, 404)
            else respond(request, 422, """{"message":"Authorization: Bearer $SYNTHETIC_TOKEN"}""")
        }

        val result = uploader.upload(packageFile(), META)

        assertEquals(listOf("GET", "PUT"), requests.map { it.method })
        assertEquals(PluginUploadResult(false, "Upload failed (HTTP 422)."), result)
        assertFalse(result.toString().contains(SYNTHETIC_TOKEN))
    }

    @Test
    fun networkErrorDoesNotExposeExceptionText() = runBlocking {
        val uploader = uploader { throw IOException("Authorization: Bearer $SYNTHETIC_TOKEN") }

        val result = uploader.upload(packageFile(), META)

        assertEquals(listOf("GET"), requests.map { it.method })
        assertEquals(PluginUploadResult(false, "Submission failed."), result)
        assertFalse(result.toString().contains(SYNTHETIC_TOKEN))
    }

    @Test
    fun unicodeIdIsEncodedAsOnePathSegment() = runBlocking {
        val uploader = uploader { chain -> respond(chain.request(), if (chain.request().method == "GET") 404 else 201) }
        val meta = META.copy(id = "作者.天气插件", name = "天气插件", author = "作者")

        assertTrue(uploader.upload(packageFile(), meta).ok)
        assertEquals(listOf("GET", "PUT"), requests.map { it.method })
        requests.forEach { request ->
            assertEquals(
                "/repos/$REPO/contents/incoming/%E4%BD%9C%E8%80%85.%E5%A4%A9%E6%B0%94%E6%8F%92%E4%BB%B6_1.2.3.zip",
                request.url.encodedPath
            )
            assertEquals("作者.天气插件_1.2.3.zip", request.url.pathSegments.last())
        }
        assertEquals("plugin: 作者.天气插件 1.2.3", putBody().getValue("message").jsonPrimitive.content)
    }

    @Test
    fun invalidOrMissingConfigNeverSendsRequest() = runBlocking {
        val file = packageFile()
        val settings = listOf(
            "" to SYNTHETIC_TOKEN,
            REPO to " ",
            REPO to "$SYNTHETIC_TOKEN\r\nX-Injected: value",
            "https://github.com/$REPO" to SYNTHETIC_TOKEN,
            "bad--owner/inbox" to SYNTHETIC_TOKEN,
            "owner/.." to SYNTHETIC_TOKEN,
            "owner/inbox?branch=main" to SYNTHETIC_TOKEN,
            "owner/in%2Fbox" to SYNTHETIC_TOKEN,
            "owner/inbox/extra" to SYNTHETIC_TOKEN
        )
        for ((repo, token) in settings) {
            val uploader = uploader(repo, token) { chain -> respond(chain.request(), 500) }

            assertFalse(uploader.isConfigured())
            assertEquals(PluginUploadResult(false, "Inbox unavailable."), uploader.upload(file, META))
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun rejectsEmptyAndOversizedPackagesBeforeRequest() = runBlocking {
        val uploader = uploader { chain -> respond(chain.request(), 403) }
        val file = temp.newFile()

        assertEquals(PluginUploadResult(false, "Package unreadable."), uploader.upload(file, META))
        RandomAccessFile(file, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
        assertEquals(PluginUploadResult(false, "Package exceeds 20 MB."), uploader.upload(file, META))
        assertTrue(requests.isEmpty())

        RandomAccessFile(file, "rw").use { it.setLength(20L * 1024 * 1024) }
        assertEquals(PluginUploadResult(false, "Lookup failed (HTTP 403)."), uploader.upload(file, META))
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test
    fun backendCancellationIsPropagated() {
        val cancellation = CancellationException("Caller cancelled")
        val backend = object : PluginPublishBackend {
            override val isConfigured = true
            override suspend fun submit(packageFile: File, meta: PluginPublishMeta): PluginUploadResult = throw cancellation
        }
        val uploader = PluginStoreUploader(context, backend)
        val file = packageFile()

        val thrown = assertThrows(CancellationException::class.java) { runBlocking { uploader.upload(file, META) } }

        assertTrue(thrown === cancellation || thrown.cause === cancellation)
    }

    @Test
    fun cancellingLookupCancelsTheHttpCallAndSkipsPut() = runBlocking {
        val lookupStarted = CompletableDeferred<Call>()
        val allowResponse = CountDownLatch(1)
        val uploader = uploader { chain ->
            lookupStarted.complete(chain.call())
            if (!allowResponse.await(10, TimeUnit.SECONDS)) throw IOException("Timed out waiting for test release")
            respond(chain.request(), 404)
        }
        val file = packageFile()
        val upload = async { uploader.upload(file, META) }
        try {
            val call = withTimeout(5_000) { lookupStarted.await() }
            val cancellation = CancellationException("Caller cancelled")
            upload.cancel(cancellation)
            val thrown = try {
                upload.await()
                throw AssertionError("Cancelled submission returned a result")
            } catch (e: CancellationException) {
                e
            }

            assertTrue(thrown === cancellation || thrown.cause === cancellation)
            assertTrue(call.isCanceled())
            assertEquals(listOf("GET"), requests.map { it.method })
        } finally {
            allowResponse.countDown()
        }
    }

    private fun uploader(
        repo: String = REPO,
        token: String = SYNTHETIC_TOKEN,
        respond: (Interceptor.Chain) -> Response
    ): PluginStoreUploader {
        val http = OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor { chain ->
            requests += chain.request()
            respond(chain)
        }.build()
        return PluginStoreUploader(context, GitHubPluginPublishBackend(context, http, repo, token))
    }

    private fun respond(request: Request, code: Int, body: String = "{}"): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Test response")
        .body(body.toResponseBody())
        .build()

    private fun putBody(): JsonObject {
        val buffer = Buffer()
        checkNotNull(requests.single { it.method == "PUT" }.body).writeTo(buffer)
        return Json.parseToJsonElement(buffer.readUtf8()) as JsonObject
    }

    private fun packageFile() = temp.newFile().apply { writeBytes(ZIP_BYTES) }

    private companion object {
        const val REPO = "sample-org/qa-inbox"
        const val SYNTHETIC_TOKEN = "synthetic-test-token-never-valid"
        const val SHA = "0123456789012345678901234567890123456789"
        val ZIP_BYTES = byteArrayOf(0x50, 0x4b, 3, 4)
        val META = PluginPublishMeta("demo.weather", "Weather", "1.2.3", "Weather demo", "demo")
    }
}
