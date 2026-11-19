package com.aicove.feature.agent.domain.provider

import com.aicove.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicove.feature.agent.data.remote.gemini.GeminiApi
import com.aicove.feature.agent.data.remote.openai.OpenAIApi
import com.aicove.feature.settings.data.remote.ModelMetadataService
import com.aicove.feature.settings.data.repository.GeneralSettingsRepository
import com.aicove.feature.settings.data.repository.ProviderKeyRotator
import com.aicove.feature.settings.domain.model.AIProviderConfig
import com.aicove.feature.settings.domain.model.ModelMetadata
import com.aicove.feature.settings.domain.model.ProviderType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderInstanceFactoryTest {
    private val rotator = mockk<ProviderKeyRotator>()
    private val metadata = mockk<ModelMetadataService>()
    private val settings = mockk<GeneralSettingsRepository>()
    private val factory = ProviderInstanceFactory(
        mockk<OpenAIApi>(), mockk<AnthropicApi>(), mockk<GeminiApi>(), rotator, metadata, settings
    )
    private val config = AIProviderConfig(
        id = "test-provider", name = "Test", type = ProviderType.OPENAI,
        apiKey = "test-key", baseUrl = "https://example.com/v1", defaultModel = "test-model",
        customHeaders = mapOf("X-Test" to "example")
    )

    private fun prepareMetadata() {
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata(
            id = "test-model", contextTokens = 128_000, outputTokens = 4096,
            supportsCustomTemperature = false
        )
        coEvery { settings.firstByteTimeoutMs() } returns 30_000L
        coEvery { settings.streamIdleTimeoutMs() } returns 20_000L
        coEvery { settings.maxNetworkRetries() } returns 2
    }

    @Test
    fun createsIndependentInstancesWithTheSameProviderSettings() = runBlocking {
        prepareMetadata()
        every { rotator.activeKey(config, "session") } returns "selected-test-key"
        val first = factory.create(config, "session")
        val second = factory.create(config, "session")
        assertNotSame(first, second)
        assertEquals("selected-test-key", first.apiKey)
        assertEquals(config.baseUrl, first.baseUrl)
        assertEquals(config.effectiveModel, first.model)
        assertEquals(config.customHeaders, first.customHeaders)
        assertEquals(4096, first.maxOutputTokens)
        assertNull(first.temperature)
        assertEquals(30_000L, first.firstByteTimeoutMs)
        assertEquals(20_000L, first.streamIdleTimeoutMs)
        assertEquals(2, first.maxNetworkRetries)
    }

    @Test
    fun visionRequestRetainsTheExistingSingleKeyBehaviour() = runBlocking {
        prepareMetadata()
        val provider = factory.create(config, "vision-session", rotateKeys = false)
        assertEquals(config.apiKey, provider.apiKey)
        assertNull(provider.keySwitcher)
        verify(exactly = 0) { rotator.activeKey(any(), any()) }
    }
}
