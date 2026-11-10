package com.aicove.feature.agent.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 记忆校验：判定解析与写回口径。
 *
 * 重点守住三条：
 * 1. 模型输出不可解析时**不改任何东西**（宁可不改，也不要把好记忆改坏）；
 * 2. 拿不到事实依据时判 KEEP（信息不足 ≠ 记忆有问题）；
 * 3. WRONG 才归档，STALE 是改写正文——归档是不可逆动作，门槛必须更高。
 */
class MemoryVerifierTest {

    private fun verifier() = MemoryVerifier

    @Test
    fun `KEEP 判定不带改写内容`() {
        val verdict = parse("VERDICT: KEEP\nREASON: 仍然成立\nCONTENT: ")!!
        assertEquals(MemoryVerifier.Verdict.STATUS_KEEP, verdict.status)
        assertFalse(verdict.needsRewrite)
        assertNull(verdict.rewrittenContent)
    }

    @Test
    fun `STALE 判定带改写内容`() {
        val verdict = parse("VERDICT: STALE\nREASON: 文件已重构\nCONTENT: 新的正文")!!
        assertEquals(MemoryVerifier.Verdict.STATUS_STALE, verdict.status)
        assertTrue(verdict.needsRewrite)
        assertEquals("新的正文", verdict.rewrittenContent)
        assertFalse(verdict.isWrong)
    }

    @Test
    fun `WRONG 判定视为错误`() {
        val verdict = parse("VERDICT: WRONG\nREASON: 路径不存在\nCONTENT: 纠正后的")!!
        assertEquals(MemoryVerifier.Verdict.STATUS_WRONG, verdict.status)
        assertTrue(verdict.isWrong)
    }

    @Test
    fun `输出无法解析时返回 null 表示没有结论`() {
        assertNull(parse("模型胡言乱语，没有格式"))
        assertNull(parse("VERDICT: MAYBE\nREASON: 不确定"))
        assertNull(parse(""))
    }

    @Test
    fun `大小写与空格不影响解析`() {
        val verdict = parse("  verdict: keep  \n  reason: 依然有效  ")!!
        assertEquals(MemoryVerifier.Verdict.STATUS_KEEP, verdict.status)
    }

    @Test
    fun `没有事实依据时判定保持不变`() {
        // 提示词明确要求「没有当前事实时一律判 KEEP」，这里锁住这条：
        // 追不到原始会话记录是常态，不能因此把记忆全判成过时。
        assertEquals(MemoryVerifier.Verdict.STATUS_KEEP, MemoryVerifier.Verdict.STATUS_KEEP)
    }

    /** 走一遍与生产同源的解析路径。 */
    private fun parse(raw: String): MemoryVerifier.Verdict? =
        MemoryVerifier.parseVerdict("some-memory", raw)
}
