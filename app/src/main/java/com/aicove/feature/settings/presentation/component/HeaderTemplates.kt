package com.aicove.feature.settings.presentation.component

/**
 * 自定义请求头的内置模板。
 *
 * 只收录「每次请求都相同、与账号无关」的静态标识头。会话 id、匿名用户 id、账号 id、
 * attestation、stainless 运行时指纹这类由客户端运行时动态生成或与账号绑定的头不在其中
 * —— 硬编码它们只会伪造出错误身份，服务端一眼就能看出对不上。
 *
 * 版本号跟随各客户端当前公开发布值，用户可在弹窗里直接改。
 */
internal data class HeaderTemplate(
    val id: String,
    val label: String,
    /** 一句话说明这个模板的用途与来源。 */
    val description: String,
    val headers: List<Pair<String, String>>
)

internal object HeaderTemplates {

    /** Codex CLI（openai/codex）。默认头只有 originator 与 User-Agent。 */
    private val CODEX = HeaderTemplate(
        id = "codex",
        label = "Codex CLI",
        description = "OpenAI Codex CLI 的客户端标识，中转站常按此区分 Codex 渠道",
        headers = listOf(
            "originator" to "codex_cli_rs",
            "User-Agent" to "codex_cli_rs/0.58.0 (Linux 6.1.0; x86_64) reqwest/0.12"
        )
    )

    /** DeepSeek Harness（deepseek-ai/deepseek-harness）。 */
    private val DSH = HeaderTemplate(
        id = "dsh",
        label = "DeepSeek Harness",
        description = "DeepSeek Harness 的应用标识（product/version (+url) 形式）",
        headers = listOf(
            "user-agent" to "deepseek-harness/0.1.0 (+https://github.com/deepseek-ai/deepseek-harness)"
        )
    )

    /** Claude Code（anthropics/claude-code）。 */
    private val CLAUDE_CODE = HeaderTemplate(
        id = "claude-code",
        label = "Claude Code",
        description = "Claude Code CLI 的客户端标识与 Anthropic 协议版本、beta 能力",
        headers = listOf(
            "User-Agent" to "claude-cli/2.1.92 (external, cli)",
            "anthropic-version" to "2023-06-01",
            "anthropic-beta" to "claude-code-20250219,context-1m-2025-08-07,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14",
            "x-app" to "cli"
        )
    )

    val ALL: List<HeaderTemplate> = listOf(CODEX, DSH, CLAUDE_CODE)
}
