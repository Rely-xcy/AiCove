package com.aicove.feature.agent.domain.mcp

import com.aicove.core.util.FileLogger
import com.aicove.feature.agent.domain.container.LinuxContainerEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StdioTransportTest {
    private val dispatcher = StandardTestDispatcher()
    private val requests = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var process: FakeProcess
    private lateinit var transport: StdioTransport

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.d(any(), any()) } returns Unit
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.w(any(), any(), any()) } returns Unit
        process = FakeProcess()
        val engine = mockk<LinuxContainerEngine>()
        every { engine.startStdioProcess(any(), any(), any(), any(), any(), any()) } returns process
        transport = StdioTransport("test", engine, "fake", emptyList(), null)
    }

    @After
    fun tearDown() {
        requests.cancel()
        dispatcher.scheduler.runCurrent()
        val readerJob = (field("scope") as CoroutineScope).coroutineContext[Job]!!
        transport.close()
        try {
            runUntil { requests.coroutineContext[Job]!!.isCompleted }
            runBlocking { withTimeout(5_000) { readerJob.join() } }
        } finally {
            unmockkObject(FileLogger)
        }
    }

    @Test
    fun repeatedCancellationAfterWriteRestoresPendingBaseline() = runBlocking {
        val anchor = requestAsync()
        val anchorId = awaitWrittenRequest()["id"]
        val baseline = pendingCount()
        assertEquals(1, baseline)

        repeat(25) {
            val cancellation = CancellationException("cancel $it")
            var observed: Throwable? = null
            val request = requests.launch {
                observed = runCatching { transport.request("tools/list", null) }.exceptionOrNull()
            }
            awaitWrittenRequest()
            assertEquals(baseline + 1, pendingCount())
            request.cancel(cancellation)
            runUntil { request.isCompleted }
            assertCancellation(cancellation, observed)
            assertEquals(baseline, pendingCount())
            assertFalse(process.destroyed)
        }

        process.respond("""{"jsonrpc":"2.0","id":$anchorId,"result":{}}""")
        runUntil { anchor.isCompleted }
        anchor.await().getOrThrow()
        assertEquals(0, pendingCount())
    }

    @Test
    fun cancellationWhileWaitingForWriterPropagatesAndCleansPending() {
        val mutex = field("writeMutex") as Mutex
        assertTrue(mutex.tryLock())
        try {
            val cancellation = CancellationException("waiting for writer")
            var observed: Throwable? = null
            val request = requests.launch {
                observed = runCatching { transport.request("tools/list", null) }.exceptionOrNull()
            }
            dispatcher.scheduler.runCurrent()
            assertEquals(1, pendingCount())
            assertTrue(process.written.isEmpty())
            request.cancel(cancellation)
            runUntil { request.isCompleted }
            assertCancellation(cancellation, observed)
            assertEquals(0, pendingCount())
            assertTrue(process.written.isEmpty())
            assertFalse(process.destroyed)
        } finally {
            mutex.unlock()
        }
    }

    @Test
    fun cancellationThrownByWriterIsNotWrapped() = runBlocking {
        val cancellation = CancellationException("write cancelled")
        process.writeFailure = cancellation
        val request = requestAsync()
        runUntil { request.isCompleted }
        assertCancellation(cancellation, request.await().exceptionOrNull())
        assertEquals(0, pendingCount())
        assertFalse(process.destroyed)
    }

    @Test
    fun responseReturnsResultAndCleansPending() = runBlocking {
        val request = requestAsync()
        val id = awaitWrittenRequest()["id"]
        process.respond("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
        runUntil { request.isCompleted }
        val response = request.await().getOrThrow()
        assertEquals(1L, response.id)
        assertEquals(JsonObject(mapOf("ok" to JsonPrimitive(true))), response.result)
        assertEquals(0, pendingCount())
        assertFalse(process.destroyed)
    }

    @Test
    fun errorResponsePreservesRpcError() = runBlocking {
        val request = requestAsync()
        val id = awaitWrittenRequest()["id"]
        process.respond("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"missing"}}""")
        runUntil { request.isCompleted }
        val error = request.await().exceptionOrNull() as McpException
        assertEquals(-32601, error.rpcCode)
        assertEquals("tools/list 返回错误 [-32601] missing", error.message)
        assertEquals(0, pendingCount())
        assertFalse(process.destroyed)
    }

    @Test
    fun writeFailurePreservesWrapperAndCause() = runBlocking {
        val rootCause = EOFException("peer closed stdin")
        val failure = IOException("broken pipe", rootCause)
        process.writeFailure = failure
        val request = requestAsync()
        runUntil { request.isCompleted }
        val error = request.await().exceptionOrNull() as McpException
        assertEquals("[test] 写入 stdin 失败: broken pipe", error.message)
        val writeCause = error.cause
        assertEquals(failure.javaClass, writeCause?.javaClass)
        assertEquals(failure.message, writeCause?.message)
        // Stack trace recovery can insert an equivalent IOException before the underlying cause.
        val observedRoot = generateSequence(writeCause) { it.cause }
            .firstOrNull { it.javaClass != failure.javaClass || it.message != failure.message }
        assertEquals(rootCause.javaClass, observedRoot?.javaClass)
        assertEquals(rootCause.message, observedRoot?.message)
        assertEquals(0, pendingCount())
    }

    @Test
    fun timeoutCleansPendingAndLeavesTransportUsable() = runBlocking {
        val request = requestAsync()
        awaitWrittenRequest()
        dispatcher.scheduler.advanceTimeBy(120_000)
        runUntil { request.isCompleted }
        val error = request.await().exceptionOrNull() as McpException
        assertEquals("[test] 请求 tools/list 超时（120000ms）", error.message)
        assertEquals(0, pendingCount())
        assertFalse(process.destroyed)

        val next = requestAsync()
        val id = awaitWrittenRequest()["id"]
        process.respond("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
        runUntil { next.isCompleted }
        next.await().getOrThrow()
        assertEquals(0, pendingCount())
    }

    @Test
    fun closeFailsAllPendingAndRejectsNewRequests() = runBlocking {
        val pendingRequests = List(3) { requestAsync().also { awaitWrittenRequest() } }
        assertEquals(3, pendingCount())
        transport.close()
        transport.close()
        runUntil { pendingRequests.all { it.isCompleted } }
        pendingRequests.forEach { assertTrue(it.await().exceptionOrNull() is McpException) }
        assertEquals(0, pendingCount())
        assertTrue(process.destroyed)
        val next = requestAsync()
        runUntil { next.isCompleted }
        assertEquals("[test] transport 已关闭", next.await().exceptionOrNull()?.message)
    }

    private fun assertCancellation(expected: CancellationException, actual: Throwable?) {
        // Stack trace recovery can copy the exception, but must not wrap it in a transport error.
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
    }

    private fun requestAsync() = requests.async {
        runCatching { transport.request("tools/list", null) }
    }

    private fun field(name: String): Any = StdioTransport::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(transport)
    }

    private fun pendingCount() = (field("pending") as Map<*, *>).size

    private fun awaitWrittenRequest(): JsonObject {
        dispatcher.scheduler.runCurrent()
        val line = process.written.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("Request was not written")
        // 写锁在 withContext 返回后释放；跑完该 continuation 才能确保取消落在响应等待阶段。
        runUntil { !(field("writeMutex") as Mutex).isLocked }
        return Json.parseToJsonElement(line) as JsonObject
    }

    private fun runUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            dispatcher.scheduler.runCurrent()
            if (condition()) return
            if (System.nanoTime() >= deadline) throw AssertionError("Coroutine did not reach expected state")
            Thread.yield()
        }
    }

    private class FakeProcess : Process() {
        val written = LinkedBlockingQueue<String>()
        @Volatile var writeFailure: Exception? = null
        @Volatile var destroyed = false
        private val stdoutBytes = LinkedBlockingQueue<Int>()
        private val stdout = object : InputStream() {
            private var ended = false
            override fun read(): Int {
                if (ended) return -1
                return stdoutBytes.take().also { if (it == -1) ended = true }
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val value = read()
                if (value == -1) return -1
                b[off] = value.toByte()
                return 1
            }
        }
        private val stdin = object : ByteArrayOutputStream() {
            override fun flush() {
                writeFailure?.let { throw it }
                if (size() == 0) return
                written.put(toString("UTF-8").trimEnd('\n'))
                reset()
            }
        }
        private val stderr = ByteArrayInputStream(byteArrayOf())

        fun respond(line: String) {
            (line + "\n").toByteArray(Charsets.UTF_8).forEach { stdoutBytes.put(it.toInt() and 0xff) }
        }

        override fun getOutputStream() = stdin
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun waitFor(): Int = exitValue()
        override fun waitFor(timeout: Long, unit: TimeUnit) = destroyed
        override fun exitValue(): Int {
            if (!destroyed) throw IllegalThreadStateException("Process is still running")
            return 0
        }
        override fun destroy() {
            destroyed = true
            stdoutBytes.offer(-1)
        }
    }
}
