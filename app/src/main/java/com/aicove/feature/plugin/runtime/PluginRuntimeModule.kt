package com.aicove.feature.plugin.runtime

import com.aicove.core.plugin.AgentLifecycleHooks
import com.aicove.feature.agent.domain.mcp.McpServerContributionSource
import com.aicove.feature.agent.domain.skill.SkillContributionSource
import com.aicove.feature.agent.domain.subagent.AgentDefinitionContributionSource
import com.aicove.feature.plugin.domain.PluginAgentSource
import com.aicove.feature.plugin.domain.PluginMcpServerSource
import com.aicove.feature.plugin.domain.PluginSkillSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class PluginRuntimeModule {
    @Binds
    abstract fun bindAgentLifecycleHooks(implementation: PluginHookDispatcher): AgentLifecycleHooks

    @Binds
    @IntoSet
    abstract fun bindSkillContributionSource(source: PluginSkillSource): SkillContributionSource

    @Binds
    @IntoSet
    abstract fun bindMcpServerContributionSource(source: PluginMcpServerSource): McpServerContributionSource

    @Binds
    @IntoSet
    abstract fun bindAgentDefinitionContributionSource(
        source: PluginAgentSource
    ): AgentDefinitionContributionSource
}
