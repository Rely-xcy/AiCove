package com.aicove.feature.agent.domain.mcp

import kotlinx.coroutines.flow.Flow

/** 外部能力向 MCP 管理器贡献的 server；实现通过 DI 集合注册。 */
interface McpServerContributionSource {
    /** 来源内容变化通知；MCP 管理器据此重连贡献的 server。 */
    val changes: Flow<Unit>

    /** 当前获准并启用的 server 配置。 */
    fun activeServers(): List<McpServerConfig>
}
