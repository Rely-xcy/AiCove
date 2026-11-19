package com.aicove.feature.plugin

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import io.mockk.every
import io.mockk.mockk
import java.util.Locale

internal fun pluginTestContext(languageTag: String): Context {
    val localeList = mockk<LocaleList>()
    every { localeList[0] } returns Locale.forLanguageTag(languageTag)
    val configuration = mockk<Configuration>()
    every { configuration.locales } returns localeList
    val appResources = mockk<Resources>()
    every { appResources.configuration } returns configuration
    val context = mockk<Context>()
    every { context.resources } returns appResources
    return context
}
