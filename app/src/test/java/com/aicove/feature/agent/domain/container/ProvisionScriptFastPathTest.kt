package com.aicove.feature.agent.domain.container

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionScriptFastPathTest {

    private fun script(): String {
        val candidates = listOf(
            File("src/main/assets/aicode/provision.sh"),
            File("app/src/main/assets/aicode/provision.sh")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 provision.sh")
    }

    @Test
    fun completedMarker_exitsBeforeLoadingLibraryOrWritingLog() {
        val text = script()
        val fastPath = text.indexOf("if [ \"\$1\" != \"--env\" ] && [ -f \"\$MARKER\" ]")
        val loadLibrary = text.indexOf(". \"\$LIB_FILE\"")
        val startupLog = text.indexOf("plog \"provision.sh 启动")

        assertTrue(fastPath >= 0)
        assertTrue(fastPath < loadLibrary)
        assertTrue(fastPath < startupLog)
        assertTrue(text.contains("[ \"\$state\" = \"\$PROVISION_VERSION\" ] && exit 0"))
        assertTrue(text.contains("[ \"\$state\" = \"\$PROVISION_SKIPPED\" ] && exit 0"))
        assertFalse(text.contains("标记已是 \$PROVISION_VERSION，跳过菜单"))
    }
}
