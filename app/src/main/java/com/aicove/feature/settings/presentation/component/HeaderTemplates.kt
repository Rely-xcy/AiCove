package com.aicove.feature.settings.presentation.component

/**
 * 自定义请求头的内置模板。
 *
 * 静态标识头直接写值；每次请求都会变的字段用占位符，由 `resolveCustomHeaders`
 * 在发请求时动态生成（同一请求内多个头共用同一个值，保证彼此对得上）：
 *
 * - `{{SESSION_UUID}}` 由会话 id 稳定派生，同一会话内固定
 * - `{{UUID}}` / `{{UUID_HEX}}` 每次请求随机
 * - `{{TIMESTAMP}}` / `{{ISO8601}}` 当前时间
 * - `{{RANDOM_8}}` / `{{RANDOM_16}}` 随机十六进制串
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

    /** Codex CLI（openai/codex）。 */
    private val CODEX = HeaderTemplate(
        id = "codex",
        label = "Codex CLI",
        description = "OpenAI Codex CLI 的客户端标识与请求追踪头",
        headers = listOf(
            "originator" to "codex_cli_rs",
            "User-Agent" to "codex_cli_rs/0.58.0 (Linux 6.1.0; x86_64) reqwest/0.12",
            "session-id" to "{{SESSION_UUID}}",
            "x-client-request-id" to "{{UUID}}",
            "x-codex-window-id" to "{{UUID}}"
        )
    )

    /** DeepSeek Harness（deepseek-ai/deepseek-harness）。 */
    private val DSH = HeaderTemplate(
        id = "dsh",
        label = "DeepSeek Harness",
        description = "DeepSeek Harness 的应用标识与会话身份",
        headers = listOf(
            "user-agent" to "deepseek-harness/0.1.0 (+https://github.com/deepseek-ai/deepseek-harness)",
            "x-deepseek-harness-user-id" to "{{SESSION_UUID}}",
            "x-deepseek-harness-session-id" to "{{SESSION_UUID}}"
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
            "x-app" to "cli",
            "x-client-request-id" to "{{UUID}}"
        )
    )

    val ALL: List<HeaderTemplate> = listOf(CODEX, DSH, CLAUDE_CODE)
}
