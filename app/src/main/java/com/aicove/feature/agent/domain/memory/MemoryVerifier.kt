package com.aicove.feature.agent.domain.memory

import com.aicove.core.util.FileLogger
import com.aicove.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆校验：对照**当前事实**判断一条长期记忆是否还成立。
 *
 * 为什么需要它（对照 Anthropic 的 dreaming 实验）：
 * 记忆是模型在某个时刻写下的判断，之后环境会变——文件重构了、配置项删了、做法被替换了。
 * 旧实现只有「没人用就归档」（按 hitCount）与「这两条是一回事」（判合并），
 * **从不检查内容对不对**。于是一条从一开始就写错的记忆会被一直注入到后续会话里，
 * 而用户看到的现象只是「AI 老拿过时信息说话」，很难归因。
 *
 * 校验的判定依据取自**当前项目文件**：记忆正文里提到的文件/符号，读它们的当前内容，
 * 交给模型对照「记忆说的」与「文件现在是这样」判断。这比「凭模型印象」可靠，
 * 也比追原始会话记录可行——那条记录在记忆写下时就已经不存在了。
 *
 * 拿不到依据时保守判 KEEP：宁可留一条可能过时的记忆，也不要因为模型不确定
 * 就改掉用户自己写的东西。
 */
@Singleton
class MemoryVerifier @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val fileAccess: FileAccessProvider
) {

    /** 单条校验的结论。 */
    data class Verdict(
        val name: String,
        /** KEEP / STALE / WRONG */
        val status: String,
        val reason: String,
        /** 改写后的正文；KEEP 时为 null。 */
        val rewrittenContent: String? = null
    ) {
        val needsRewrite: Boolean get() = status != STATUS_KEEP && !rewrittenContent.isNullOrBlank()
        val isWrong: Boolean get() = status == STATUS_WRONG

        companion object {
            const val STATUS_KEEP = "KEEP"
            const val STATUS_STALE = "STALE"
            const val STATUS_WRONG = "WRONG"
        }
    }

    /**
     * 校验一批记忆，逐条独立判定。
     *
     * @param complete 一次性模型调用（给 user prompt，返回模型输出）。
     *   null 表示拿不到模型，此时不做任何改动——校验是「有则更好」的能力，
     *   拿不到模型时静默跳过比报错更合适（治理本身仍能跑本地规则）。
     * @param maxChecks 本轮最多校验多少条：校验每条都要读文件 + 一次模型调用，
     *   全量跑既慢又贵。按「最可能出错」排序取前若干条。
     */
    suspend fun verify(
        projectRoot: String,
        candidates: List<Memory>,
        complete: (suspend (String) -> String?)?,
        maxChecks: Int = MAX_CHECKS_PER_ROUND
    ): List<Verdict> {
        if (complete == null || candidates.isEmpty()) return emptyList()

        val verdicts = mutableListOf<Verdict>()
        for (memory in candidates.take(maxChecks)) {
            val verdict = verifyOne(memory, projectRoot, complete)
            if (verdict != null) verdicts += verdict
        }
        if (verdicts.isNotEmpty()) {
            FileLogger.i(
                TAG,
                "记忆校验完成：${verdicts.size} 条，" +
                    "保持 ${verdicts.count { it.status == Verdict.STATUS_KEEP }}、" +
                    "过时 ${verdicts.count { it.status == Verdict.STATUS_STALE }}、" +
                    "错误 ${verdicts.count { it.status == Verdict.STATUS_WRONG }}"
            )
        }
        return verdicts
    }

    /** 校验单条；模型输出不可解析时返回 null（当作没结论，不改这条）。 */
    private suspend fun verifyOne(
        memory: Memory,
        projectRoot: String,
        complete: suspend (String) -> String?
    ): Verdict? {
        val facts = gatherFacts(memory, projectRoot)
        val raw = runCatching { complete(buildUserPrompt(memory, facts)) }.getOrNull() ?: return null
        return parseVerdict(memory.name, raw)
    }

    /**
     * 把校验结论写回磁盘。
     *
     * STALE（已过时）→ 改写正文并保存；WRONG（从一开始就错）→ 归档退场。
     * KEEP 不动。
     *
     * 写回前会确认条目还在（治理可能刚好归档了它），并且**描述不变**——
     * 描述参与注入清单与召回排序，模型改描述等于改了这条记忆的“索引”。
     *
     * @return 实际改写/归档的条数。
     */
    suspend fun applyRewrites(verdicts: List<Verdict>, projectRoot: String): Int {
        if (verdicts.isEmpty()) return 0
        val all = runCatching { memoryRepository.listMemories(projectRoot) }.getOrDefault(emptyList())
        val byName = all.associateBy { it.name }
        var changed = 0
        for (verdict in verdicts) {
            val memory = byName[verdict.name] ?: continue
            when {
                verdict.isWrong -> {
                    // 从头就错：留着比不留着更糟（会被当规则注入），直接归档。
                    if (memoryRepository.deleteMemory(memory.name, memory.scope, projectRoot)) {
                        changed++
                        FileLogger.i(TAG, "记忆「${memory.name}」校验为错误，已归档：${verdict.reason}")
                    }
                }

                verdict.needsRewrite -> {
                    val content = verdict.rewrittenContent ?: continue
                    if (content == memory.content) continue
                    val saved = memoryRepository.saveMemory(
                        name = memory.name,
                        description = memory.description,
                        content = content,
                        scope = memory.scope,
                        projectRoot = projectRoot,
                        kind = memory.kind,
                        source = memory.source,
                        createdAt = memory.createdAt,
                        pinned = memory.pinned
                    )
                    if (saved) {
                        changed++
                        FileLogger.i(TAG, "记忆「${memory.name}」校验为过时，已改写：${verdict.reason}")
                    }
                }
            }
        }
        return changed
    }

    /**
     * 取「当前事实」：读记忆里提到的文件片段。
     *
     * 只取与项目相关的路径（工作区内或绝对路径），读不到就跳过——没有事实不等于记忆有问题。
     * 每个文件只取头部若干行：多数记忆记的是「这个文件是干什么的」，头几行足够判断。
     */
    private suspend fun gatherFacts(memory: Memory, projectRoot: String): String {
        val paths = extractPaths(memory.content).take(MAX_FACT_FILES)
        if (paths.isEmpty()) return ""
        val sb = StringBuilder()
        for (path in paths) {
            val text = runCatching { fileAccess.readFile(path) }.getOrNull() ?: continue
            if (text.isBlank()) continue
            sb.append("--- ").append(path).append(" ---\n")
            sb.append(text.take(MAX_FACT_CHARS)).append('\n')
        }
        return sb.toString()
    }

    /** 从记忆正文里抽出看起来像文件路径的片段。 */
    private fun extractPaths(content: String): List<String> {
        val result = linkedSetOf<String>()
        for (match in PATH_RX.findAll(content)) {
            val raw = match.value.trim().trim('"', '\'', '`', ',', '，', '。', '：', ':')
            if (raw.length < 4 || raw.length > MAX_PATH_LEN) continue
            if (raw.any { it == '*' || it == '?' || it == ' ' }) continue
            if (!raw.contains('/')) continue
            // 只保留看起来像真实路径的：带扩展名，或以 / 或 ./ 开头，或含已知目录段。
            val looksLikePath = raw.contains('.') || raw.startsWith("/") || raw.startsWith("./")
            if (looksLikePath) result += raw
        }
        return result.toList()
    }

    /** 给模型的 user prompt：记忆正文 + 当前事实（可为空）。 */
    private fun buildUserPrompt(memory: Memory, facts: String): String = buildString {
        appendLine("## 记忆")
        appendLine("名称: ${memory.name}")
        appendLine("描述: ${memory.description}")
        appendLine("正文:")
        appendLine(memory.content)
        if (facts.isNotBlank()) {
            appendLine()
            appendLine("## 当前事实（来自项目文件）")
            append(facts)
        }
    }

    /**
     * 解析模型输出：VERDICT / REASON / CONTENT 三行。
     */
    companion object {
        private const val TAG = "MemoryVerifier"

        /**
         * 解析模型输出：VERDICT / REASON / CONTENT 三行。
         *
         * 放在伴生对象里当纯函数：解析口径要能被单测直接锁住（输出格式一改就全盘失效），
         * 而它本身不依赖任何依赖注入的东西。
         */
        internal fun parseVerdict(name: String, raw: String): Verdict? {
            val verdictLine = raw.lineSequence().firstOrNull { it.trimStart().startsWith("VERDICT", ignoreCase = true) }
                ?: return null
            val status = verdictLine.substringAfter(':', "").trim().uppercase()
            if (status !in setOf(Verdict.STATUS_KEEP, Verdict.STATUS_STALE, Verdict.STATUS_WRONG)) return null
            val reason = raw.lineSequence()
                .firstOrNull { it.trimStart().startsWith("REASON", ignoreCase = true) }
                ?.substringAfter(':', "")?.trim().orEmpty()
            val content = raw.lineSequence()
                .firstOrNull { it.trimStart().startsWith("CONTENT", ignoreCase = true) }
                ?.substringAfter(':', "")?.trim()
            return Verdict(
                name = name,
                status = status,
                reason = reason,
                rewrittenContent = content?.takeIf { it.isNotBlank() && status != Verdict.STATUS_KEEP }
            )
        }

        /** 单轮最多校验多少条：每条要读文件 + 一次模型调用，全量太贵。 */
        const val MAX_CHECKS_PER_ROUND = 5

        /** 最多取几个文件作为事实依据。 */
        const val MAX_FACT_FILES = 3

        /** 单个文件最多取多少字符。 */
        const val MAX_FACT_CHARS = 1_500

        /** 路径最长多少字符（超长多半是散文误匹配）。 */
        const val MAX_PATH_LEN = 160

        /**
         * 路径匹配：带斜杠的一段标识符。
         *
         * 允许 `src/main/kotlin/Foo.kt`、`app/src/...`、`./local/x`、`/abs/path` 这些形态，
         * 也允许 `Foo.kt` 这种带扩展名的裸文件名（少见，但记忆里确实这么写）。
         */
        val PATH_RX = Regex("""[A-Za-z0-9_./@\-]*[A-Za-z0-9_][A-Za-z0-9_./@\-]*\.[A-Za-z0-9]{1,8}""")
    }
}
