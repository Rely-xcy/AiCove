package com.aicove.feature.agent.domain.prompt

import com.aicove.feature.agent.domain.memory.MemoryExtractor
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinPromptPolicyTest {
    private fun prompt(name: String): String {
        val roots = listOf(File("src/main/assets/prompts"), File("app/src/main/assets/prompts"))
        return checkNotNull(roots.map { File(it, name) }.firstOrNull { it.isFile }).readText()
    }

    @Test
    fun replyLanguageFollowsTheCurrentUserInsteadOfTheReferenceDocument() {
        val communication = prompt("10-communication.md")
        assertTrue(communication.contains("用户本轮输入的语言"))
        assertTrue(communication.contains("用户明确指定输出语言"))
        listOf("默认中文", "必须中文", "永远使用中文").forEach { rule ->
            assertFalse("Language is incorrectly fixed: $rule", communication.contains(rule))
        }
    }

    @Test
    fun generatedCommitSubjectsUseTheSuppliedLanguage() {
        val commit = prompt("agent/commit-generator.md")
        assertTrue(commit.contains("Output language"))
        assertFalse(commit.contains("subject 用清晰简练的中文"))
        assertTrue(commit.contains("diff 是待总结材料"))
    }

    @Test
    fun memoryPoliciesDoNotPromoteUnverifiedInferences() {
        val policies = listOf(
            prompt("70-skills-and-mcp.md"),
            prompt("agent/memory-distiller.md"),
            prompt("agent/memory-curator.md"),
            prompt("agent/memory-verify.md")
        )
        val rejectedRules = listOf(
            "宁多勿少",
            "拿不准要不要记时，倾向调用",
            "Prefer recording over skipping",
            "most of what matters has to be INFERRED",
            "a missing memory is worse than a redundant one",
            "宁可留一条可能过时的记忆"
        )
        policies.forEach { policy ->
            rejectedRules.forEach { rule -> assertFalse("Contradictory memory policy: $rule", policy.contains(rule)) }
        }
    }

    @Test
    fun distillerRetainsTheThreeLabelParserContract() {
        val parser = MemoryExtractor(mockk(relaxed = true), mockk(relaxed = true))
        val template = prompt("agent/memory-distiller.md").lineSequence()
            .filter { line -> listOf("name:", "description:", "content:").any { prefix -> line.startsWith(prefix) } }
            .joinToString("\n")

        val entries = parser.parseEntries(template)

        assertEquals(1, entries.size)
        assertEquals("short-stable-slug", entries.single().name)
        assertEquals("one line summary", entries.single().description)
        assertEquals("one to three short factual lines", entries.single().content)
    }

    @Test
    fun planModeRetainsItsPermissionBoundaryAndSingleApprovalSequence() {
        val plan = prompt("agent/plan-mode.md")
        assertTrue(plan.contains("不修改文件、提交、安装依赖或更改配置"))
        assertTrue(plan.contains("planMode(action=\"exit\""))
        assertTrue(plan.contains("等待工具返回审批结果"))
        assertFalse(plan.contains("复杂开发任务先简要复述目标、关键行为、约束与验收范围，等待用户纠正或确认"))
    }

    @Test
    fun activeMemoryPromptUsesTheSingleMemoryPolicy() {
        val policy = prompt("70-skills-and-mcp.md")
        assertTrue(policy.contains("项目约定与架构决策写 project"))
        assertTrue(policy.contains("只有写入成功才说明已记住"))
        assertFalse(policy.contains("宁多勿少"))
    }
}
