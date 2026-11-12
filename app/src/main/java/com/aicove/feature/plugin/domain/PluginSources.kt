package com.aicove.feature.plugin.domain

import com.aicove.feature.agent.domain.mcp.McpServerConfig
import com.aicove.feature.agent.domain.mcp.McpServerContributionSource
import com.aicove.feature.agent.domain.skill.Skill
import com.aicove.feature.agent.domain.skill.SkillContributionSource
import com.aicove.feature.agent.domain.subagent.AgentDefinition
import com.aicove.feature.agent.domain.subagent.AgentDefinitionContributionSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * 插件贡献的技能来源。内容来自手机端插件包，不经 FileAccessProvider，
 * 因此不会写入远程服务器的技能目录，SSH 模式下同样可用。
 */
@Singleton
class PluginSkillSource @Inject constructor(
    private val manager: PluginManager
) : SkillContributionSource {

    override fun listSkills(): List<Skill> = manager.activeSkills()

    /** 全部插件技能，不论插件是否启用，供插件管理界面展示。 */
    fun listAllSkills(): List<Skill> = manager.allSkills()

    override fun loadInstructions(name: String): String? =
        listSkills().firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
}

/** 插件贡献的子代理来源。 */
@Singleton
class PluginAgentSource @Inject constructor(
    private val manager: PluginManager
) : AgentDefinitionContributionSource {

    override fun listDefinitions(): List<AgentDefinition> = manager.activeAgents()

    /** 含已停用插件的定义，供还原在途子会话使用。 */
    override fun listAllDefinitions(): List<AgentDefinition> = manager.allAgents()

    /** 插件集合的变更序号，并入子代理清单的缓存版本。 */
    override val revision: Long get() = manager.revision
}

/** 插件贡献的 MCP server 来源；注册和连接权限均满足时才返回。 */
@Singleton
class PluginMcpServerSource @Inject constructor(
    private val manager: PluginManager
) : McpServerContributionSource {
    override val changes: Flow<Unit> = manager.plugins.drop(1).map { Unit }

    override fun activeServers(): List<McpServerConfig> =
        manager.activeMcpServers().map { it.config }
}
