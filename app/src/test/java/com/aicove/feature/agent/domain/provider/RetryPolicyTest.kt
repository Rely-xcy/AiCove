package com.aicove.feature.agent.domain.provider

import com.aicove.core.net.NetworkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 触发重试的异常 → 用户可见错误摘要（[Throwable.toRetryErrorInfo]）的分类逻辑。
 */
class RetryPolicyTest {

    @Test
    fun timeout_covers_waiting_for_headers() = runTest {
        var cancelled = false
        try {
            withFirstContentTimeout(100) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            org.junit.Assert.fail("Expected timeout")
        } catch (e: SocketTimeoutException) {
            assertEquals(true, cancelled)
        }
    }

    @Test
    fun timeout_closes_body_and_is_retriable() = runTest {
        var closed = false
        try {
            withFirstContentTimeout(100) { guard ->
                guard.attach { closed = true }
                awaitCancellation()
            }
            org.junit.Assert.fail("Expected timeout")
        } catch (e: SocketTimeoutException) {
            assertEquals(true, closed)
            assertEquals(true, isRetriableNetworkError(e))
        }
    }

    @Test
    fun first_content_allows_long_stream_and_preserves_flow_context() = runTest {
        val values = flow {
            withFirstContentTimeout(100) { guard ->
                delay(50)
                guard.receivedContent()
                emit(1)
                delay(1000)
                emit(2)
            }
        }.toList()
        assertEquals(listOf(1, 2), values)
    }

    @Test(timeout = 5000)
    fun timeout_unblocks_blocking_body_read() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val released = java.util.concurrent.CountDownLatch(1)
        try {
            withFirstContentTimeout(50) { guard ->
                guard.attach { released.countDown() }
                released.await()
            }
            org.junit.Assert.fail("Expected timeout")
        } catch (e: SocketTimeoutException) {
            assertEquals(0L, released.count)
        }
    }

    @Test(timeout = 10000)
    fun real_http1_body_read_is_cancelled_on_timeout() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val fixture = BlockingHttp1Stream()
        var call: retrofit2.Call<okhttp3.ResponseBody>? = null
        try {
            call = fixture.createCall()
            val activeCall = requireNotNull(call)
            withFirstContentTimeout(500) { guard ->
                val body = guard.awaitBody(activeCall)
                body.use {
                    guard.attach { body.close() }
                    body.charStream().buffered().readLine()
                }
            }
            org.junit.Assert.fail("Expected timeout")
        } catch (e: SocketTimeoutException) {
            assertEquals(true, call?.isCanceled)
        } finally {
            fixture.close(call)
        }
    }

    @Test(timeout = 10000)
    fun real_http1_body_read_is_cancelled_on_network_loss() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        NetworkStatus.update(true)
        val fixture = BlockingHttp1Stream()
        var call: retrofit2.Call<okhttp3.ResponseBody>? = null
        try {
            call = fixture.createCall()
            val activeCall = requireNotNull(call)
            val read = async {
                try {
                    withFirstContentTimeout(5_000) { guard ->
                        val body = guard.awaitBody(activeCall)
                        body.use {
                            guard.attach { body.close() }
                            fixture.bodyReadStarted.countDown()
                            body.charStream().buffered().readLine()
                        }
                    }
                    false
                } catch (_: IOException) {
                    activeCall.isCanceled
                }
            }
            assertEquals(true, fixture.bodyReadStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            NetworkStatus.update(false)
            assertEquals(true, kotlinx.coroutines.withTimeout(2_000) { read.await() })
        } finally {
            NetworkStatus.update(true)
            fixture.close(call)
        }
    }

    @Test
    fun zero_timeout_does_not_limit_wait() = runTest {
        withFirstContentTimeout(0) { delay(1000) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun user_cancellation_closes_body_without_timeout_conversion() = runTest {
        var closed = false
        var cancellation = false
        val job = launch {
            try {
                withFirstContentTimeout(1000) { guard ->
                    guard.attach { closed = true }
                    awaitCancellation()
                }
            } catch (e: CancellationException) {
                cancellation = true
                throw e
            }
        }
        runCurrent()
        advanceTimeBy(50)
        job.cancel()
        job.join()
        assertEquals(true, closed)
        assertEquals(true, cancellation)
    }

    /**
     * 复刻 provider 的流式结构：重试循环跑在 channelFlow 里，attempt 由首字节超时兜底。
     *
     * buffer(0) 使 send 成为会合点：首个元素被消费端取走后，第二次 send 挂起等待下一个
     * 接收者，100ms 定时器恰在此期间触发——首字节超时的取消信号落在挂起的 send 上，
     * 这正是线上「Flow exception transparency is violated / LLM 调用失败」的触发路径。
     * 因此 provider 侧必须用 channelFlow + send（adapter 有注释说明），若退回 flow + emit，
     * 本测试会以 IllegalStateException（透明性违规）失败。
     */
    @Test
    fun firstContent_timeout_during_suspended_send_retries_cleanly() = runTest {
        val events = mutableListOf<String>()
        var attempt = 0
        val chunks = channelFlow {
            streamWithStaircaseRetry(
                maxRetries = 1,
                attemptOnce = { onContent ->
                    withFirstContentTimeout(100) { guard ->
                        if (attempt++ == 0) {
                            // 首内容前发出非内容状态；buffer(0) 下第二次 send 挂起，首内容超时须打断它。
                            send("progress")
                            send("waiting")
                            org.junit.Assert.fail("首次尝试应在首内容前的挂起 send 上被超时打断")
                        } else {
                            guard.receivedContent()
                            onContent()
                            send("recovered")
                        }
                    }
                },
                onRetry = { a, _, _ -> send("retry-$a") }
            )
        }.buffer(0)

        chunks.collect {
            delay(1_000)
            events.add(it)
        }
        assertEquals(listOf("progress", "retry-1", "recovered"), events)
    }

    @Test
    fun networkLossCancelsNonStreamingCoroutineAndRecoveryRestartsRequest() = runTest {
        NetworkStatus.update(true)
        var requests = 0
        var firstAttemptCancelled = false
        val retries = mutableListOf<Int>()
        try {
            val result = async {
                retryStaircase(
                    maxRetries = 1,
                    onRetry = { attempt, _, _ -> retries += attempt }
                ) {
                    requests++
                    if (requests == 1) {
                        try {
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            firstAttemptCancelled = true
                        }
                    }
                    "recovered"
                }
            }
            runCurrent()
            assertEquals(1, requests)

            NetworkStatus.update(false)
            runCurrent()
            assertEquals(true, firstAttemptCancelled)
            assertEquals(1, requests)
            assertEquals(listOf(1), retries)

            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("offline retry must wait for recovery", 1, requests)

            NetworkStatus.update(true)
            runCurrent()
            assertEquals("recovered", result.await())
            assertEquals(2, requests)
        } finally {
            NetworkStatus.update(true)
        }
    }

    @Test
    fun streamDisconnectBeforeContentCancelsAndRetriesAfterRecovery() = runTest {
        NetworkStatus.update(true)
        var requests = 0
        val callClosed = CompletableDeferred<Unit>()
        val retries = mutableListOf<Int>()
        try {
            val result = async {
                streamWithStaircaseRetry(
                    maxRetries = 1,
                    attemptOnce = { onContent ->
                        requests++
                        if (requests == 1) {
                            withFirstContentTimeout(5_000) { guard ->
                                guard.attach { callClosed.complete(Unit) }
                                callClosed.await()
                                throw IOException("call cancelled on network loss")
                            }
                        } else {
                            withFirstContentTimeout(5_000) { guard ->
                                guard.receivedContent()
                                onContent()
                            }
                        }
                    },
                    onRetry = { attempt, _, _ -> retries += attempt }
                )
            }
            runCurrent()
            NetworkStatus.update(false)
            runCurrent()
            callClosed.await()
            assertEquals(1, requests)

            NetworkStatus.update(true)
            runCurrent()

            result.await()
            assertEquals(2, requests)
            assertEquals(listOf(1), retries)
        } finally {
            NetworkStatus.update(true)
        }
    }

    @Test
    fun networkSwitchCancelsActiveStreamAndRetriesImmediately() = runTest {
        NetworkStatus.update(true)
        var requests = 0
        val callClosed = CompletableDeferred<Unit>()
        try {
            val result = async {
                streamWithStaircaseRetry(
                    maxRetries = 1,
                    attemptOnce = { onContent ->
                        requests++
                        if (requests == 1) {
                            withFirstContentTimeout(5_000) { guard ->
                                guard.attach { callClosed.complete(Unit) }
                                callClosed.await()
                                throw IOException("old network socket is stale")
                            }
                        } else {
                            withFirstContentTimeout(5_000) { guard ->
                                guard.receivedContent()
                                onContent()
                            }
                        }
                    }
                )
            }
            runCurrent()
            NetworkStatus.update(true, networkChanged = true)
            runCurrent()
            callClosed.await()

            result.await()
            assertEquals(2, requests)
        } finally {
            NetworkStatus.update(true)
        }
    }

    @Test
    fun partialContentDisconnect_isNotRetriedAndDoesNotClearPartial() = runTest {
        var requests = 0
        var keyFailures = 0
        val retries = mutableListOf<Int>()
        val received = mutableListOf<String>()
        try {
            channelFlow {
                streamWithStaircaseRetry(
                    maxRetries = 2,
                    onKeyFailure = { _, _ -> keyFailures++; true },
                    attemptOnce = { onContent ->
                        requests++
                        onContent()
                        send("partial")
                        throw IOException("Connection reset")
                    },
                    onRetry = { attempt, _, _ -> retries += attempt; send("retry-$attempt") }
                )
            }.collect { received += it }
            org.junit.Assert.fail("Expected IOException")
        } catch (_: IOException) {
            assertEquals(1, requests)
            assertEquals(0, keyFailures)
            assertEquals(emptyList<Int>(), retries)
            assertEquals(listOf("partial"), received)
        }
    }

    @Test
    fun partialContentNonRetryableFailureDoesNotReplayEvenWhenKeySwitchIsAvailable() = runTest {
        var requests = 0
        val keyRetryAllowed = mutableListOf<Boolean>()
        val retries = mutableListOf<Int>()
        val received = mutableListOf<String>()
        try {
            channelFlow {
                streamWithStaircaseRetry(
                    maxRetries = 2,
                    onKeyFailure = { _, canRetry -> keyRetryAllowed += canRetry; true },
                    attemptOnce = { onContent ->
                        requests++
                        onContent()
                        send("partial")
                        throw httpError(401)
                    },
                    onRetry = { attempt, _, _ -> retries += attempt; send("retry-$attempt") }
                )
            }.collect { received += it }
            org.junit.Assert.fail("Expected HttpException")
        } catch (e: HttpException) {
            assertEquals(401, e.code())
            assertEquals(1, requests)
            assertEquals(listOf(false), keyRetryAllowed)
            assertEquals(emptyList<Int>(), retries)
            assertEquals(listOf("partial"), received)
        }
    }

    @Test
    fun userCancellationAfterContent_isPassedThroughUnchanged() = runTest {
        val cancellation = CancellationException("caller cancelled")
        var requests = 0
        try {
            streamWithStaircaseRetry(
                maxRetries = 2,
                attemptOnce = { onContent ->
                    requests++
                    onContent()
                    throw cancellation
                }
            )
            org.junit.Assert.fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals(true, e === cancellation)
            assertEquals(1, requests)
        }
    }

    @Test
    fun callerCancellationBeforeContentIsNotRetried() = runTest {
        var requests = 0
        var retries = 0
        val request = launch {
            retryStaircase(
                maxRetries = 2,
                onRetry = { _, _, _ -> retries++ }
            ) {
                requests++
                kotlinx.coroutines.awaitCancellation()
            }
        }

        runCurrent()
        assertEquals(1, requests)
        request.cancel()
        request.join()

        assertEquals(1, requests)
        assertEquals(0, retries)
    }

    @Test
    fun networkRecoveryDuringBackoffWakesRetryImmediately() = runTest {
        NetworkStatus.update(true)
        var requests = 0
        val retries = mutableListOf<Int>()
        try {
            val result = async {
                streamWithStaircaseRetry(
                    maxRetries = 1,
                    attemptOnce = {
                        requests++
                        if (requests == 1) throw IOException("Connection reset")
                    },
                    onRetry = { attempt, _, _ -> retries += attempt }
                )
            }
            runCurrent()
            assertEquals(1, requests)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(1, requests)

            NetworkStatus.update(false)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("offline retry must wait for recovery", 1, requests)

            val recoveryTime = testScheduler.currentTime
            NetworkStatus.update(true)
            runCurrent()
            assertEquals("recovery must wake the retry without advancing virtual time", 2, requests)
            assertEquals(recoveryTime, testScheduler.currentTime)
            result.await()
            assertEquals(listOf(1), retries)
        } finally {
            NetworkStatus.update(true)
        }
    }

    @Test
    fun networkSwitchWhileOnlineBackoffWakesRetryImmediately() = runTest {
        NetworkStatus.update(true)
        var requests = 0
        try {
            val result = async {
                retryStaircase(maxRetries = 1) {
                    requests++
                    if (requests == 1) throw IOException("Connection reset")
                    "recovered"
                }
            }

            runCurrent()
            assertEquals(1, requests)
            advanceTimeBy(200)
            runCurrent()
            NetworkStatus.update(true, networkChanged = true)
            runCurrent()

            assertEquals("online network switch must wake retry without waiting full backoff", 2, requests)
            assertEquals("recovered", result.await())
        } finally {
            NetworkStatus.update(true)
        }
    }

    @Test
    fun networkRecoveryDoesNotSkipServerRetryAfter() = runTest {
        NetworkStatus.update(true)
        var requests = 0
        val retries = mutableListOf<Int>()
        try {
            val result = async {
                retryStaircase(
                    maxRetries = 1,
                    onRetry = { attempt, _, _ ->
                        retries += attempt
                        NetworkStatus.update(false)
                    }
                ) {
                    requests++
                    if (requests == 1) throw httpErrorWithRetryAfter("30")
                    "recovered"
                }
            }
            runCurrent()
            assertEquals(1, requests)
            assertEquals(listOf(1), retries)

            NetworkStatus.update(true)
            runCurrent()
            assertEquals("recovery before Retry-After must not retry early", 1, requests)
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(1, requests)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, requests)
            assertEquals("recovered", result.await())
        } finally {
            NetworkStatus.update(true)
        }
    }

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

    /**
     * 带 Retry-After 头的 429。Retrofit 只有 `error(int, ResponseBody)`（不带响应头）与
     * `error(ResponseBody, okhttp3.Response)`（响应头取自 rawResponse）两个重载，带头的那个要自己造 raw response。
     */
    private fun httpErrorWithRetryAfter(seconds: String): HttpException =
        HttpException(
            Response.error<Any>(
                "{}".toResponseBody(null),
                okhttp3.Response.Builder()
                    .code(429)
                    .message("Too Many Requests")
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .request(okhttp3.Request.Builder().url("https://example.com/").build())
                    .header("Retry-After", seconds)
                    .build()
            )
        )

    @Test
    fun retryAfter_largeDeltaSecondsSaturatesAtMaximumInsteadOfOverflowing() {
        val huge = httpErrorWithRetryAfter("999999999999999999999999999999999999999")

        assertEquals(60_000L, extractRetryAfterMillis(huge))
        assertEquals(60_000L, retryDelayMillis(0, huge))
    }

    @Test
    fun retryAfter_isReadThroughWrapperCauseChain() {
        assertEquals(30_000L, extractRetryAfterMillis(httpErrorWithRetryAfter("30")))
        // 包装一层也要读得到：与 isRateLimitError 同一套 cause 链穿透，
        // 否则把 enrichWithHttpErrorBody 挪进重试封装内时 Retry-After 会静默失效。
        assertEquals(
            30_000L,
            extractRetryAfterMillis(IllegalStateException("wrapped", httpErrorWithRetryAfter("30")))
        )
        assertNull(extractRetryAfterMillis(IOException("no http")))
        assertNull(extractRetryAfterMillis(httpError(429)))
    }

    @Test
    fun http_429_maps_to_rate_limit() {
        val info = httpError(429).toRetryErrorInfo()
        assertEquals(RetryErrorKind.RATE_LIMIT, info.kind)
        assertEquals(429, info.statusCode)
    }

    @Test
    fun http_5xx_maps_to_server_error() {
        assertEquals(RetryErrorKind.SERVER_ERROR, httpError(500).toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SERVER_OVERLOADED, httpError(503).toRetryErrorInfo().kind)
        assertEquals(503, httpError(503).toRetryErrorInfo().statusCode)
        assertEquals(RetryErrorKind.SERVER_ERROR, httpError(502).toRetryErrorInfo().kind)
    }

    @Test
    fun timeout_exceptions_map_to_timeout() {
        assertEquals(RetryErrorKind.TIMEOUT, SocketTimeoutException().toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.TIMEOUT, InterruptedIOException().toRetryErrorInfo().kind)
    }

    @Test
    fun network_exceptions_map_to_specific_kinds() {
        assertEquals(RetryErrorKind.DNS_FAILED, UnknownHostException().toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.CONNECTION_REFUSED, ConnectException().toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SSL_ERROR, SSLException("handshake failed").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.CONNECTION_RESET, IOException("Connection reset by peer").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.CONNECTION_RESET, IOException("unexpected end of stream").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.NETWORK, IOException("SSE 流被中断").toRetryErrorInfo().kind)
    }

    @Test
    fun stream_api_codes_map_to_specific_kinds() {
        assertEquals(RetryErrorKind.RATE_LIMIT, StreamApiException("rate_limit_exceeded", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.RATE_LIMIT, StreamApiException("insufficient_quota", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SERVER_OVERLOADED, StreamApiException("server_is_overloaded", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SERVER_ERROR, StreamApiException("internal_error", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.UNKNOWN, StreamApiException("some_other", "m").toRetryErrorInfo().kind)
    }

    @Test
    fun retriable_network_error_classification() {
        // 429 归多 Key 切换，不再走网络重试
        assertEquals(false, isRetriableNetworkError(httpError(429)))
        // 408 与 5xx 仍视为瞬时故障
        assertEquals(true, isRetriableNetworkError(httpError(408)))
        assertEquals(true, isRetriableNetworkError(httpError(500)))
        assertEquals(true, isRetriableNetworkError(httpError(503)))
        // 鉴权/权限/计费类由多 Key 切换处理，不重试
        assertEquals(false, isRetriableNetworkError(httpError(401)))
        assertEquals(false, isRetriableNetworkError(httpError(402)))
        assertEquals(false, isRetriableNetworkError(httpError(403)))
        // 流内限流/额度码同样直接交给多 Key 切换
        assertEquals(false, isRetriableNetworkError(StreamApiException("rate_limit_exceeded", "m")))
        assertEquals(false, isRetriableNetworkError(StreamApiException("insufficient_quota", "m")))
        // 流内服务端故障仍可重试
        assertEquals(true, isRetriableNetworkError(StreamApiException("server_is_overloaded", "m")))
    }

    @Test
    fun status_code_null_for_non_http_errors() {
        assertNull(SocketTimeoutException().toRetryErrorInfo().statusCode)
        assertNull(IOException().toRetryErrorInfo().statusCode)
        assertNull(StreamApiException("server_is_overloaded", "m").toRetryErrorInfo().statusCode)
    }
}

private class BlockingHttp1Stream {
    val bodyReadStarted = java.util.concurrent.CountDownLatch(1)
    private val release = java.util.concurrent.CountDownLatch(1)
    private var server: java.net.ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile private var connectedSocket: java.net.Socket? = null
    private var client: okhttp3.OkHttpClient? = null

    fun createCall(): retrofit2.Call<okhttp3.ResponseBody> {
        val localServer = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server = localServer
        try {
            serverThread = Thread {
                try {
                    localServer.accept().use { socket ->
                        connectedSocket = socket
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                            flush()
                        }
                        release.await()
                    }
                } catch (_: java.io.IOException) {
                }
            }.apply { isDaemon = true; start() }
            val localClient = okhttp3.OkHttpClient.Builder()
                .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client = localClient
            val api = retrofit2.Retrofit.Builder()
                .baseUrl("http://127.0.0.1:${localServer.localPort}/")
                .client(localClient)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build()
                .create(com.aicove.feature.agent.data.remote.openai.OpenAIApi::class.java)
            return api.streamChatCompletion(
                "http://127.0.0.1:${localServer.localPort}/", "test", emptyMap(),
                com.aicove.feature.agent.data.remote.openai.ChatCompletionRequest(
                    model = "test", messages = emptyList(), stream = true
                )
            )
        } catch (error: Throwable) {
            close(null)
            throw error
        }
    }

    fun close(call: retrofit2.Call<okhttp3.ResponseBody>?) {
        runCatching { call?.cancel() }
        release.countDown()
        runCatching { connectedSocket?.close() }
        runCatching { server?.close() }
        runCatching { serverThread?.join(1_000) }
        runCatching { client?.connectionPool?.evictAll() }
        runCatching { client?.dispatcher?.executorService?.shutdown() }
    }
}
