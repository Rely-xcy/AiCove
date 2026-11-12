package com.aicove.feature.plugin.runtime

import com.aicove.core.plugin.AgentLifecycleHooks
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PluginRuntimeModule {
    @Binds
    abstract fun bindAgentLifecycleHooks(implementation: PluginHookDispatcher): AgentLifecycleHooks
}
