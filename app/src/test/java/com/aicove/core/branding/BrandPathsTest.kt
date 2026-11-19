package com.aicove.core.branding

import org.junit.Assert.assertEquals
import org.junit.Test

class BrandPathsTest {
    @Test
    fun primaryBrandAndLegacyProtocolHaveDistinctPrefixes() {
        assertEquals("AICOVE_", BrandPaths.ENV_PREFIX)
        assertEquals("AICODE_", BrandPaths.Legacy.ENV_PREFIX)
        assertEquals("aicode", BrandPaths.ASSETS_DIR_NAME)
    }

    @Test
    fun directoryMigrationHasDistinctNewAndLegacyRoots() {
        assertEquals("/root/.aicove", BrandPaths.CONFIG_ROOT)
        assertEquals(".aicove", BrandPaths.CONFIG_DIR_NAME)
        assertEquals("aicove", BrandPaths.HOST_DIR_NAME)
        assertEquals("/root/.aicode", BrandPaths.Legacy.CONFIG_ROOT)
        assertEquals(".aicode", BrandPaths.Legacy.CONFIG_DIR_NAME)
        assertEquals("aicode", BrandPaths.Legacy.HOST_DIR_NAME)
    }
}
