package com.aicove.core.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainMotionPolicyTest {
    @Test
    fun `main panel motion changes drawing without animating layout height`() {
        val source = locateSource().readText()

        assertFalse(source.contains("expandVertically"))
        assertFalse(source.contains("shrinkVertically"))
        assertTrue(source.contains("slideInVertically"))
        assertTrue(source.contains("scaleIn"))
    }

    private fun locateSource(): File {
        val candidates = listOf(
            File("src/main/java/com/aicove/core/ui/MainMotion.kt"),
            File("app/src/main/java/com/aicove/core/ui/MainMotion.kt")
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("MainMotion.kt not found from ${File(".").absolutePath}")
    }
}
