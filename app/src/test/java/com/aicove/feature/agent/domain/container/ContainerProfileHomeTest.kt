package com.aicove.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerProfileHomeTest {

    @Test
    fun homePath_usesEngineDefaultWhenProfileDoesNotOverrideHome() {
        assertEquals("/root", ContainerProfile.BUILTIN_ALPINE.homePath)
    }

    @Test
    fun homePath_usesProfileEnvironmentOverride() {
        assertEquals("/home/dev", profile(env = mapOf("HOME" to "/home/dev")).homePath)
    }

    @Test
    fun homePath_blankOverrideFallsBackToEngineDefault() {
        assertEquals("/root", profile(env = mapOf("HOME" to " ")).homePath)
    }

    private fun profile(env: Map<String, String>) = ContainerProfile(
        id = "custom-home",
        name = "Custom",
        rootfsSource = RootfsSource.LocalFile("file:///tmp/rootfs.tar.gz"),
        shellPath = null,
        env = env,
        isBuiltin = false
    )
}
