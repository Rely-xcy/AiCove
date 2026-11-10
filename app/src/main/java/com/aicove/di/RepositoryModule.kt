package com.aicove.di

import com.aicove.feature.settings.domain.repository.AIProviderRepository
import com.aicove.feature.settings.data.repository.AIProviderRepositoryImpl
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
}
