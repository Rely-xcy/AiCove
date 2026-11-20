package com.aicove.feature.plugin.data

import android.content.Context
import com.aicove.BuildConfig
import com.aicove.R
import com.aicove.feature.plugin.domain.PluginManifestParser
import com.aicove.feature.plugin.domain.SemVer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import okio.source

@Module
@InstallIn(SingletonComponent::class)
abstract class PluginPublishModule {
    @Binds
    abstract fun bindPublishBackend(backend: GitHubPluginPublishBackend): PluginPublishBackend
}

@Singleton
class GitHubPluginPublishBackend internal constructor(
    private val context: Context,
    private val client: OkHttpClient,
    repo: String,
    token: String
) : PluginPublishBackend {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context,
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build(),
        BuildConfig.PLUGIN_INBOX_REPO,
        BuildConfig.PLUGIN_INBOX_TOKEN
    )

    private val contentsUrl = inboxContentsUrl(repo)
    private val token = token.trim()
    override val isConfigured: Boolean = contentsUrl != null &&
        this.token.isNotEmpty() && this.token.all { it in '!'..'~' }

    override suspend fun submit(packageFile: File, meta: PluginPublishMeta): PluginUploadResult {
        val inbox = contentsUrl?.takeIf { isConfigured }
            ?: return failure(R.string.muse_plugin_publish_unconfigured)
        if (!PluginManifestParser.isValidPluginId(meta.id) || SemVer.parse(meta.version) == null) {
            return failure(R.string.muse_plugin_publish_manifest_invalid)
        }
        val bytes = try {
            packageFile.source().buffer().use { source ->
                // 文件校验后仍可能变动，实际读取也必须有上限。
                if (source.request(PluginStoreUploader.MAX_PACKAGE_BYTES + 1)) {
                    return failure(R.string.muse_plugin_publish_too_large, PluginStoreUploader.MAX_PACKAGE_MB)
                }
                source.readByteArray()
            }
        } catch (_: IOException) {
            return failure(R.string.muse_plugin_publish_unreadable)
        }
        if (bytes.isEmpty()) return failure(R.string.muse_plugin_publish_unreadable)

        val url = inbox.newBuilder().addPathSegment("${meta.id}_${meta.version}.zip").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")

        currentCoroutineContext().ensureActive()
        val lookup = execute(request.header("Accept", "application/vnd.github.object+json").get().build()) { response ->
            Lookup(response.code, if (response.code == 200) readSha(response) else null)
        }
        val sha = when (lookup.code) {
            404 -> null
            200 -> lookup.sha ?: return failure(R.string.muse_plugin_publish_lookup_invalid)
            else -> return failure(R.string.muse_plugin_publish_lookup_failed, lookup.code)
        }

        val body = buildJsonObject {
            put("message", "plugin: ${meta.id} ${meta.version}")
            put("content", Base64.getEncoder().encodeToString(bytes))
            sha?.let { put("sha", it) }
        }.toString().toRequestBody(JSON)
        currentCoroutineContext().ensureActive()
        val status = execute(request.header("Accept", "application/vnd.github+json").put(body).build()) { it.code }
        return if (status == 200 || status == 201) {
            PluginUploadResult(true, context.getString(R.string.muse_plugin_publish_submitted))
        } else {
            failure(R.string.muse_plugin_publish_write_failed, status)
        }
    }

    private fun readSha(response: Response): String? {
        val metadata = response.peekBody(MAX_LOOKUP_BYTES + 1).use { body ->
            if (body.contentLength() > MAX_LOOKUP_BYTES) return null
            try {
                Json.parseToJsonElement(body.string()) as? JsonObject
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        return (metadata?.get("sha") as? JsonPrimitive)
            ?.takeIf { it.isString && BLOB_SHA.matches(it.content) }
            ?.content
    }

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use(read)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(result)
                }
            })
        }

    private fun failure(message: Int): PluginUploadResult = PluginUploadResult(false, context.getString(message))

    private fun failure(message: Int, value: Int): PluginUploadResult =
        PluginUploadResult(false, context.getString(message, value))

    private data class Lookup(val code: Int, val sha: String?)

    private companion object {
        val JSON = "application/json".toMediaType()
        val OWNER = Regex("(?!.*--)[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        val REPO = Regex("[A-Za-z0-9_.-]{1,100}")
        val BLOB_SHA = Regex("[0-9a-fA-F]{40}")
        // GitHub 会把不超过 1 MiB 的文件内容一并放进 JSON（base64 加换行）。
        const val MAX_LOOKUP_BYTES = 2L * 1024 * 1024

        fun inboxContentsUrl(repo: String): HttpUrl? {
            val segments = repo.trim().split('/')
            if (segments.size != 2) return null
            val (owner, name) = segments
            if (!OWNER.matches(owner) || !REPO.matches(name) || name == "." || name == ".." ||
                name.endsWith(".git", ignoreCase = true)
            ) return null
            return HttpUrl.Builder()
                .scheme("https")
                .host("api.github.com")
                .addPathSegment("repos")
                .addPathSegment(owner)
                .addPathSegment(name)
                .addPathSegment("contents")
                .addPathSegment("incoming")
                .build()
        }
    }
}
