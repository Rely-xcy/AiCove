package com.aicove.feature.git.presentation

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.lifecycle.viewModelScope
import com.aicove.feature.agent.domain.workflow.AgentWorkflow
import com.aicove.feature.git.domain.GitRepository
import com.aicove.feature.settings.data.repository.LanguageSettingsRepository
import com.aicove.feature.settings.data.repository.ThemeSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitViewModelLanguageTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<GitRepository>()
    private val workflow = mockk<AgentWorkflow>()
    private val language = mockk<LanguageSettingsRepository>()
    private val context = mockk<Context>()
    private val models = mutableListOf<GitViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.notReadyHint() } returns "deferred"
        coEvery { repository.diffForCommitMessage() } returns "diff --git a/a.kt b/a.kt"
        coEvery { workflow.generateCommitMessage(any(), any()) } returns "fix: sample"
    }

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun model() = GitViewModel(
        repository, context, mockk<ThemeSettingsRepository>(), language, workflow
    ).also(models::add)

    @Test
    fun selectedLanguageIsPassedWithoutForcingChinese() {
        listOf("en", "ja", "zh-Hant").forEach { tag ->
            coEvery { language.snapshot() } returns tag
            var result: String? = null

            model().generateCommitMessage { result = it }

            assertEquals("fix: sample", result)
            coVerify(exactly = 1) { workflow.generateCommitMessage(any(), tag) }
        }
    }

    @Test
    fun systemLanguageIsUsedWhenTheAppFollowsSystem() {
        coEvery { language.snapshot() } returns null
        val resources = mockk<Resources>()
        val configuration = mockk<Configuration>()
        val locales = mockk<LocaleList>()
        every { configuration.locales } returns locales
        every { context.resources } returns resources
        every { resources.configuration } returns configuration
        every { locales[0] } returns Locale.JAPAN
        var result: String? = null

        model().generateCommitMessage { result = it }

        assertEquals("fix: sample", result)
        coVerify(exactly = 1) { workflow.generateCommitMessage(any(), "ja-JP") }
    }
}
