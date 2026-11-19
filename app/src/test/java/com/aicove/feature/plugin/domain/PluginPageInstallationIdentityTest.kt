package com.aicove.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PluginPageInstallationIdentityTest {
    private val page = PluginPage(
        pluginId = "demo.one", localId = "config", title = LocalizedText.single("Config"),
        slot = null, pageFile = "config.html", icon = "settings", installDir = File("plugin"),
        pluginVersion = "1.0.0"
    )

    @Test
    fun sameVersionReinstallDoesNotReuseThePreviousWebViewKey() {
        val first = page.copy(installRevision = "first-install")
        val second = page.copy(installRevision = "second-install")
        assertNotEquals(first.cacheKey, second.cacheKey)
    }

    @Test
    fun reopeningTheSameInstallationKeepsItsCacheKey() {
        val installed = page.copy(installRevision = "same-install")
        assertEquals(installed.cacheKey, installed.copy().cacheKey)
    }
}
