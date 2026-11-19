package com.aicove.feature.agent.domain.container

import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量读取器的单飞与缓存：并发只发一次命令、缓存键随模式 / 工作区变化、失败回退且不反复重试。
 * 命令执行用一个假 runner（可数次数、可注入延迟），不连 SSH。
 */
class RemoteSkillBulkReaderTest {

    private val calls = AtomicInteger()
    private val response = AtomicReference<CommandResult?>(null)
    private val context = AtomicReference<RemoteSkillBulkContext?>(contextFor("/srv/ws"))

    private val runner = object : RemoteBulkCommandRunner {
        override suspend fun runUnbounded(command: String, timeoutMs: Long): CommandResult? {
            calls.incrementAndGet()
            delay(RUNNER_DELAY_MS)
            return response.get()
        }
    }

    private val reader = RemoteSkillBulkReader(
        RemoteSkillBulkContextSource { context.get() },
        runner
    )

    private fun contextFor(workspace: String) =
        RemoteSkillBulkContext("REMOTE_SSH|$workspace|/home/u", workspace, "/home/u")

    /** 造一份四根俱全的输出；[content] 为技能正文，[workspace] 决定项目级根的绝对路径。 */
    private fun successOutput(content: String = "正文", workspace: String = "/srv/ws"): CommandResult {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val text = buildString {
            append("B\tSG\n")
            append("F\t/home/u/.aicove/skills/demo/SKILL.md\t${bytes.size}\n")
            append("C\t$encoded\n")
            append("D\t0\n")
            append("B\tSP\nF\t$workspace/.aicove/skills/proj/SKILL.md\t${bytes.size}\nC\t$encoded\nD\t0\n")
            append("B\tAG\nF\t/home/u/.aicove/agents/helper.md\t${bytes.size}\nC\t$encoded\nD\t0\n")
            append("B\tAP\nF\t$workspace/.aicove/agents/project-helper.md\t${bytes.size}\nC\t$encoded\nD\t0\n")
            append("END\n")
        }
        return CommandResult(text, 0)
    }

    @Test
    fun filesUnder_localOrNotReadyContextFallsBackWithoutCommand() {
        context.set(null)

        assertNull(reader.filesUnder("~/.aicove/skills"))
        assertEquals(0, calls.get())
    }

    @Test
    fun filesUnder_unknownRootFallsBackWithoutCommand() {
        response.set(successOutput())

        assertNull(reader.filesUnder("~/.aicove/custom-skills"))
        assertEquals(0, calls.get())
    }

    @Test
    fun filesUnder_oneCommandServesAllFourRoots() {
        response.set(successOutput())

        assertEquals(setOf("demo/SKILL.md"), reader.filesUnder("~/.aicove/skills")!!.keys)
        assertEquals(setOf("proj/SKILL.md"), reader.filesUnder("~/workspace/.aicove/skills")!!.keys)
        assertEquals(setOf("helper.md"), reader.filesUnder("~/.aicove/agents")!!.keys)
        assertEquals(setOf("project-helper.md"), reader.filesUnder("~/workspace/.aicove/agents")!!.keys)
        assertEquals(1, calls.get())
    }

    @Test
    fun filesUnder_concurrentCallsShareASingleScan() {
        response.set(successOutput())
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(ArrayList<Map<String, String>>())
        val threads = (1..3).map {
            Thread {
                start.await()
                reader.filesUnder("~/.aicove/skills")?.let { files -> results += files }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(THREAD_JOIN_TIMEOUT_MS) }

        assertEquals(1, calls.get())
        assertEquals(3, results.size)
        assertTrue(results.all { it.containsKey("demo/SKILL.md") })
    }

    @Test
    fun filesUnder_cacheKeyFollowsWorkspace() {
        response.set(successOutput())
        reader.filesUnder("~/.aicove/skills")
        assertEquals(1, calls.get())

        context.set(contextFor("/srv/other"))
        response.set(successOutput(workspace = "/srv/other"))
        assertEquals(setOf("proj/SKILL.md"), reader.filesUnder("~/workspace/.aicove/skills")!!.keys)
        assertEquals(2, calls.get())
    }

    @Test
    fun filesUnder_cacheKeyFollowsMode() {
        response.set(successOutput())
        reader.filesUnder("~/.aicove/skills")
        assertEquals(1, calls.get())

        context.set(RemoteSkillBulkContext("LOCAL_PROOT|/srv/ws|/home/u", "/srv/ws", "/home/u"))
        reader.filesUnder("~/.aicove/skills")
        assertEquals(2, calls.get())
    }

    @Test
    fun filesUnder_connectionChangesDuringReadDiscardsOldResult() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val changingReader = RemoteSkillBulkReader(
            RemoteSkillBulkContextSource { context.get() },
            object : RemoteBulkCommandRunner {
                override suspend fun runUnbounded(command: String, timeoutMs: Long): CommandResult? {
                    started.countDown()
                    assertTrue(release.await(THREAD_JOIN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
                    return successOutput("old server")
                }
            }
        )
        val result = java.util.concurrent.FutureTask { changingReader.filesUnder("~/.aicove/skills") }
        val thread = Thread(result)
        thread.start()
        try {
            assertTrue(started.await(THREAD_JOIN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
            context.set(contextFor("/srv/ws").copy(cacheKey = "other-server-generation"))
        } finally {
            release.countDown()
            thread.join(THREAD_JOIN_TIMEOUT_MS)
        }
        assertTrue(!thread.isAlive)
        assertNull(result.get(THREAD_JOIN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun filesUnder_invalidateForcesNextScan() {
        response.set(successOutput())
        reader.filesUnder("~/.aicove/skills")
        reader.invalidate()
        reader.filesUnder("~/.aicove/skills")

        assertEquals(2, calls.get())
    }

    @Test
    fun filesUnder_commandFailureFallsBackAndIsNotRetriedImmediately() {
        response.set(CommandResult("exit=3\n", 3))

        assertNull(reader.filesUnder("~/.aicove/skills"))
        assertNull(reader.filesUnder("~/.aicove/skills"))
        assertEquals(1, calls.get())
    }

    @Test
    fun filesUnder_missingRunnerResultFallsBack() {
        response.set(null)

        assertNull(reader.filesUnder("~/.aicove/skills"))
    }

    @Test
    fun filesUnder_truncatedOutputFallsBack() {
        val full = successOutput()
        response.set(CommandResult(full.output, 0, outputTruncated = true))

        assertNull(reader.filesUnder("~/.aicove/skills"))
    }

    @Test
    fun filesUnder_outputWithoutEndFallsBack() {
        val full = successOutput()
        response.set(CommandResult(full.output.removeSuffix("END\n"), 0))

        assertNull(reader.filesUnder("~/.aicove/skills"))
    }

    private companion object {
        const val RUNNER_DELAY_MS = 120L
        const val THREAD_JOIN_TIMEOUT_MS = 10_000L
    }
}
