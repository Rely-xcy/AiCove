package com.aicove.feature.agent.presentation.component

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定稿正文「首帧即终态高度」的策略回归。
 *
 * 背景：定稿正文曾经一律异步解析（`immediate = false`），第一帧渲染裸文本、解析完成后才换成
 * 表格/代码块，item 高度突变。LazyColumn 按索引 + 像素偏移锚定，下方内容整体位移一下，表现成
 * 「纯滑动也跳」。这里钉住两件事：
 * 1. 策略层：什么情况下必须在首帧同步解析（[shouldParseImmediately]）；
 * 2. 解析层：同一段文本同步解析确实能当场拿到终态结构（表格/代码块节点），不需要等异步回调。
 */
class MarkdownFirstFrameLayoutTest {

    @Test
    fun `定稿正文首帧同步解析`() {
        assertTrue(
            "定稿正文必须首帧同步解析，否则先按裸文本排一版再改高度，下方内容整体位移",
            shouldParseImmediately(streaming = false, textLength = 1_200)
        )
    }

    @Test
    fun `流式正文永不首帧同步解析`() {
        assertFalse(
            "流式文本每帧都在变，同步解析等于每帧卡主线程",
            shouldParseImmediately(streaming = true, textLength = 64)
        )
    }

    @Test
    fun `超长单块退回异步`() {
        assertFalse(
            "超长单块（无法拆分的巨型代码块/表格）一次同步解析就能拉爆帧时间",
            shouldParseImmediately(streaming = false, textLength = MARKDOWN_SYNC_PARSE_MAX_CHARS + 1)
        )
        assertTrue(
            "预算边界内的正文仍要首帧同步",
            shouldParseImmediately(streaming = false, textLength = MARKDOWN_SYNC_PARSE_MAX_CHARS)
        )
    }

    @Test
    fun `长文拆块后的每个切片都落在首帧同步预算内`() {
        // 长文进入渲染前已被 splitLongContent 拆成有界 chunk；只要每块都在预算内，
        // 「首帧即终态」对长文同样成立，而单块高度有界保证了同步解析的成本有界。
        val long = buildString {
            repeat(40) { idx ->
                append("第 ").append(idx).append(" 段正文，用来把这条消息撑到需要拆块的长度。\n")
                append("这一行是同一段的第二行，内容里带 **加粗** 与 `行内代码`。\n\n")
            }
        }
        assertTrue("用例本身要足够长才能触发拆块", long.length > 2_000)
        val chunks = splitLongContent(long)
        assertTrue("长文必须拆成多块", chunks.size > 1)
        chunks.forEachIndexed { idx, chunk ->
            assertTrue(
                "第 $idx 块长 ${chunk.length}，超出首帧同步预算后该块会退回异步、首帧高度仍会变",
                shouldParseImmediately(streaming = false, textLength = chunk.length)
            )
        }
    }

    /** 递归收集 AST 里的所有节点类型（表格/代码围栏可能嵌在子节点里，只看顶层会漏）。 */
    private fun collectTypes(node: ASTNode): Set<IElementType> {
        val out = mutableSetOf<IElementType>()
        fun walk(n: ASTNode) {
            out += n.type
            n.children.forEach(::walk)
        }
        walk(node)
        return out
    }

    @Test
    fun `同一段正文同步解析当场得到终态结构`() {
        // 对应 immediate = true 的实际效果：库在 remember 里直接 parseBlocking()，
        // 第一帧拿到的就是 Success（含 TABLE / CODE_FENCE 节点），不存在 Loading 中间态。
        val parser = MarkdownParser(GFMFlavourDescriptor())
        val text = """
            | 列 A | 列 B |
            | --- | --- |
            | 1 | 2 |

            ```kotlin
            val x = 1
            ```
        """.trimIndent()

        val types = collectTypes(parser.buildMarkdownTreeFromString(text))

        assertTrue(
            "表格必须当场解析成 TABLE 节点；拿不到就说明首帧只能按裸文本排版，高度会突变",
            types.contains(GFMElementTypes.TABLE)
        )
        assertTrue(
            "代码围栏必须当场解析成 CODE_FENCE 节点（行高与裸文本不同）",
            types.contains(MarkdownElementTypes.CODE_FENCE)
        )
    }

    @Test
    fun `巨型代码块保持整块且因此退回异步`() {
        // 首帧同步解析的成本上界依赖「单块不长」，而 [splitLongContent] 明确不拆代码围栏：
        // 一个几万字符的代码块会原样作为单块，超出 [MARKDOWN_SYNC_PARSE_MAX_CHARS] 后退回异步。
        // 这条钉住两侧的约定——拆块不破坏围栏，策略也不会对超大单块同步解析。
        val longCode = buildString {
            appendLine("```kotlin")
            repeat(400) { i -> appendLine("val v$i = \"一段足够长的代码行，用来把围栏撑到几万字符 $i\"") }
            appendLine("```")
        }
        assertTrue("用例本身要超过同步预算", longCode.length > MARKDOWN_SYNC_PARSE_MAX_CHARS)

        val chunks = splitLongContent(longCode)
        assertFalse(
            "代码围栏必须整块保留，不能被拆成多个不带围栏的碎片",
            chunks.size > 1
        )
        assertFalse(
            "超大单块必须退回异步，否则一次同步解析就能拉爆帧时间",
            shouldParseImmediately(streaming = false, textLength = chunks.first().length)
        )
    }
}
