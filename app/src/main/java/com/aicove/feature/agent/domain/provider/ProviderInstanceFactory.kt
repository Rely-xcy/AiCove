package com.aicove.feature.agent.domain.provider

import com.aicove.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicove.feature.agent.data.remote.gemini.GeminiApi
import com.aicove.feature.agent.data.remote.openai.OpenAIApi
import com.aicove.feature.settings.data.remote.ModelMetadataService
import com.aicove.feature.settings.data.repository.GeneralSettingsRepository
import com.aicove.feature.settings.data.repository.ProviderKeyRotator
import com.aicove.feature.settings.domain.model.AIProviderConfig
import com.aicove.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderInstanceFactory @Inject constructor(
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val keyRotator: ProviderKeyRotator,
    private val modelMetadataService: ModelMetadataService,
    private val generalSettingsRepository: GeneralSettingsRepository
) {
    suspend fun create(config: AIProviderConfig, scopeId: String?, rotateKeys: Boolean = true): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        val activeKeys = config.effectiveApiKeys
        provider.apiKey = if (rotateKeys) {
            keyRotator.activeKey(config, scopeId)
                ?: throw IllegalStateException("Provider has no usable key: ${config.id}")
        } else config.apiKey
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (rotateKeys && activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, scopeId, failedKey, triedKeys)
                        if (switched == null) {
                            throw AllKeysFailedException("All provider keys failed: ${config.id}", error)
                        }
                        KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = scopeId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        provider.maxOutputTokens = metadata.outputTokens
        provider.reasoningCapability = metadata.toReasoningCapability()
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }
}
