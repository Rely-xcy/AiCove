package com.aicove.feature.plugin.runtime

import com.aicove.core.util.FileLogger
import com.aicove.feature.agent.domain.container.ContainerProfile
import com.aicove.feature.agent.domain.container.LinuxContainerEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginProcessTransportTest {
    private val dispatcher = StandardTestDispatcher()
    private val requests = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var process: FakeProcess
    private lateinit var transport: PluginProcessTransport

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.d(any(), any()) } returns Unit
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.w(any(), any(), any()) } returns Unit
        process = FakeProcess()
        val engine = mockk<LinuxContainerEngine>()
        every { engine.startStdioProcess(any(), any(), any(), any(), any(), any()) } returns process
        transport = PluginProcessTransport(
            pluginId = "test",
            engine = engine,
            profile = ContainerProfile.BUILTIN_ALPINE,
            workingDir = "/test",
            program = "fake",
            programArgs = emptyList(),
            extraEnv = emptyMap(),
            onRequest = { _, _ -> JsonNull },
            onNotification = { _, _ -> },
            onExit = {}
        )
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
                observed = runCatching {
                    transport.request("test", JsonObject(emptyMap()), 120_000)
                }.exceptionOrNull()
            }
            awaitWrittenRequest()
            assertEquals(baseline + 1, pendingCount())
            request.cancel(cancellation)
            runUntil { request.isCompleted }
            assertSame(cancellation, observed)
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
                observed = runCatching {
                    transport.request("test", JsonObject(emptyMap()), 120_000)
                }.exceptionOrNull()
            }
            dispatcher.scheduler.runCurrent()
            assertEquals(1, pendingCount())
            assertTrue(process.written.isEmpty())
            request.cancel(cancellation)
            runUntil { request.isCompleted }
            assertSame(cancellation, observed)
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
        assertSame(cancellation, request.await().exceptionOrNull())
        assertEquals(0, pendingCount())
        assertFalse(process.destroyed)
    }

    @Test
    fun responseReturnsResultAndCleansPending() = runBlocking {
        val request = requestAsync()
        val id = awaitWrittenRequest()["id"]
        process.respond("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
        runUntil { request.isCompleted }
        assertEquals(JsonObject(mapOf("ok" to JsonPrimitive(true))), request.await().getOrThrow())
        assertEquals(0, pendingCount())
        assertFalse(process.destroyed)
    }

    @Test
    fun errorResponsePreservesPluginError() = runBlocking {
        val request = requestAsync()
        val id = awaitWrittenRequest()["id"]
        process.respond("""{"jsonrpc":"2.0","id":$id,"error":{"code":"BAD_REQUEST","message":"missing"}}""")
        runUntil { request.isCompleted }
        val error = request.await().exceptionOrNull() as PluginProcessException
        assertEquals("BAD_REQUEST", error.code)
        assertEquals("[test] missing", error.message)
        assertEquals(0, pendingCount())
        assertFalse(process.destroyed)
    }

    @Test
    fun writeFailurePreservesPluginWriteError() = runBlocking {
        process.writeFailure = IOException("broken pipe")
        val request = requestAsync()
        runUntil { request.isCompleted }
        val error = request.await().exceptionOrNull() as PluginProcessException
        assertEquals("PLUGIN_WRITE_FAILED", error.code)
        assertEquals("[test] 写入 stdin 失败: broken pipe", error.message)
        assertEquals(0, pendingCount())
    }

    @Test
    fun timeoutCleansPendingAndClosesTransport() = runBlocking {
        val request = requestAsync(timeoutMs = 100)
        awaitWrittenRequest()
        dispatcher.scheduler.advanceTimeBy(100)
        runUntil { request.isCompleted }
        val error = request.await().exceptionOrNull() as PluginProcessException
        assertEquals("PLUGIN_TIMEOUT", error.code)
        assertEquals("[test] test 超时（100ms）", error.message)
        assertEquals(0, pendingCount())
        assertTrue(process.destroyed)
        val next = requestAsync()
        runUntil { next.isCompleted }
        assertEquals("PLUGIN_CLOSED", (next.await().exceptionOrNull() as PluginProcessException).code)
    }

    @Test
    fun closeFailsAllPendingAndRejectsNewRequests() = runBlocking {
        val pendingRequests = List(3) { requestAsync().also { awaitWrittenRequest() } }
        assertEquals(3, pendingCount())
        transport.close()
        transport.close()
        runUntil { pendingRequests.all { it.isCompleted } }
        pendingRequests.forEach {
            assertEquals("PLUGIN_EXITED", (it.await().exceptionOrNull() as PluginProcessException).code)
        }
        assertEquals(0, pendingCount())
        assertTrue(process.destroyed)
        val next = requestAsync()
        runUntil { next.isCompleted }
        assertEquals("PLUGIN_CLOSED", (next.await().exceptionOrNull() as PluginProcessException).code)
    }

    private fun requestAsync(timeoutMs: Long = 120_000) = requests.async {
        runCatching { transport.request("test", JsonObject(emptyMap()), timeoutMs) }
    }

    private fun field(name: String): Any = PluginProcessTransport::class.java.getDeclaredField(name).run {
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
