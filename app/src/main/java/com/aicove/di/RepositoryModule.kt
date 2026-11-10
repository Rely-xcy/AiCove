package com.aicove.di

import com.aicove.core.config.ConfigGateway
import com.aicove.feature.settings.domain.repository.AIProviderRepository
import com.aicove.feature.settings.data.repository.AIProviderRepositoryImpl
import com.aicove.feature.settings.data.repository.GeneralSettingsConfigGateway
import com.aicove.feature.credentials.domain.repository.CredentialRepository
import com.aicove.feature.credentials.data.repository.FileCredentialRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAIProviderRepository(
        aiProviderRepositoryImpl: AIProviderRepositoryImpl
    ): AIProviderRepository

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(
        fileCredentialRepository: FileCredentialRepository
    ): CredentialRepository

    /**
     * 配置通道的实现绑定。
     *
     * 工具在 feature/agent、实现在这里（feature/settings）：中间隔一层 core 接口，
     * 两个 feature 互不 import，避开 agent ↔ settings 的循环依赖。
     */
    @Binds
    @Singleton
    abstract fun bindConfigGateway(
        gateway: GeneralSettingsConfigGateway
    ): ConfigGateway
}
