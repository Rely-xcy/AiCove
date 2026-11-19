package com.aicove.feature.plugin.data.index

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIndexParserTest {
    @Test
    fun museEntry_preservesPackageUrlAndVersion() {
        val index = parseEntries(
            """{
                "id": "community.demo-kit",
                "name": {"default": "Demo Kit", "zh-CN": "插件演示套件"},
                "description": {"default": "Demo plugin", "zh-CN": "演示插件"},
                "author": "community",
                "repository": "https://github.com/xzyy03/Muse-Plugins-Store",
                "version": "1.2.0",
                "package_url": "$PACKAGE_URL"
            }"""
        )

        assertEquals(1, index.schemaVersion)
        assertEquals(0, index.rejectedPluginCount)
        val plugin = index.plugins.single()
        assertEquals("1.2.0", plugin.version)
        assertEquals(PACKAGE_URL, plugin.packageUrl)
        assertEquals("插件演示套件", plugin.name.values["zh-cn"])
    }

    @Test
    fun directPackage_missingOrInvalidVersion_rejectsEntry() {
        listOf(null, "", "v1.2.0", "1.2", " 1.2.0 ", "latest").forEach { version ->
            val index = parseEntries(
                entry("community.valid"),
                entry("community.invalid", version = version, packageUrl = PACKAGE_URL)
            )
            assertEquals(listOf("community.valid"), index.plugins.map { it.id })
            assertEquals(1, index.rejectedPluginCount)
        }
    }

    @Test
    fun directPackage_unsafeOrMalformedUrl_rejectsEntry() {
        listOf(
            "http://example.com/plugin.zip",
            "https:///plugin.zip",
            "https://user:password@example.com/plugin.zip",
            "https://example.com/plugin.zip#fragment",
            "https://example.com/plugin.zip#",
            "https://example.com:65536/plugin.zip",
            "https://example.com:0/plugin.zip",
            "https://example.com/plugin zip",
            "https://example.com/%ZZ",
            "file:///plugin.zip",
            "//example.com/plugin.zip",
            ""
        ).forEach { url ->
            val index = parseEntries(
                entry("community.valid"),
                entry("community.invalid", version = "1.2.0", packageUrl = url)
            )
            assertEquals(url, listOf("community.valid"), index.plugins.map { it.id })
            assertEquals(url, 1, index.rejectedPluginCount)
        }
    }

    @Test
    fun mixedEntries_countsDecodeAndValidationFailures() {
        val index = parseEntries(
            entry("community.valid"),
            entry("一束挽风.wb-gateway", version = "1.5.2", packageUrl = PACKAGE_URL),
            """{"id":"community.missing-fields"}""",
            entry("community.invalid-name").replace("\"name\":\"Plugin\"", "\"name\":42"),
            "null"
        )

        assertEquals(listOf("community.valid", "一束挽风.wb-gateway"), index.plugins.map { it.id })
        assertEquals(3, index.rejectedPluginCount)
        assertTrue(com.aicove.feature.plugin.domain.PluginManifestParser.isValidPluginId("一束挽风.wb-gateway"))
    }

    @Test
    fun duplicateId_stillFailsEvenWhenAnEntryIsInvalid() {
        assertThrows(PluginIndexException::class.java) {
            parseEntries(entry("community.same"), """{"id":"community.same"}""")
        }
    }

    @Test
    fun malformedTopLevelOrSchema_fails() {
        listOf(
            "{}",
            "[]",
            """{"schema_version":2,"plugins":[]}""",
            """{"schema_version":"1","plugins":[]}""",
            """{"schema_version":1}""",
            """{"schema_version":1,"plugins":null}""",
            """{"schema_version":1,"plugins":{}}"""
        ).forEach { json ->
            assertThrows(PluginIndexException::class.java) { PluginIndexParser.parse(json.toByteArray()) }
        }
    }

    @Test
    fun allEntriesRejected_failsInsteadOfReturningEmptyStore() {
        assertThrows(PluginIndexException::class.java) {
            parseEntries(entry("../invalid-plugin"))
        }
        assertEquals(emptyList<PluginIndexPlugin>(), parseEntries().plugins)
    }

    @Test
    fun museUnicodePublisherAndId_areAccepted() {
        val json = entry("一束挽风.wb-gateway", version = "1.5.2", packageUrl = PACKAGE_URL)
            .replace("\"author\":\"community\"", "\"author\":\"一束挽风\"")
        val plugin = parseEntries(json).plugins.single()
        assertEquals("一束挽风", plugin.author)
        assertEquals("一束挽风.wb-gateway", plugin.id)
    }

    @Test
    fun packageDigest_isValidatedAndPreserved() {
        val digest = "ab".repeat(32)
        val valid = entry("community.digest", version = "1.2.0", packageUrl = PACKAGE_URL)
            .dropLast(1) + ",\"package_sha256\":\"$digest\"}"
        assertEquals(digest, parseEntries(valid).plugins.single().packageSha256)
        listOf("", "ab", "x".repeat(64), "$digest ").forEach { invalid ->
            val malformed = valid.replace(digest, invalid)
            val index = parseEntries(entry("community.valid"), malformed)
            assertEquals(listOf("community.valid"), index.plugins.map { it.id })
            assertEquals(1, index.rejectedPluginCount)
        }
    }

    @Test
    fun releaseEntries_keepOptionalVersionAndNoPackageUrl() {
        val index = parseEntries(
            entry("community.latest"),
            entry("community.pinned", version = "1.2.0")
        )
        assertEquals(0, index.rejectedPluginCount)
        assertNull(index.plugins[0].version)
        assertEquals("1.2.0", index.plugins[1].version)
        index.plugins.forEach { assertNull(it.packageUrl) }
    }

    @Test
    fun rejectedCount_isComputedAndNotSerialized() {
        val index = parseEntries(entry("community.valid"), "null")
        val encoded = PluginIndexJson.encodeToString(index)
        assertFalse(encoded.contains("rejectedPluginCount"))
        assertEquals(0, PluginIndexJson.decodeFromString<PluginIndexDocument>(encoded).rejectedPluginCount)
    }

    private fun parseEntries(vararg entries: String): PluginIndexDocument =
        PluginIndexParser.parse("""{"schema_version":1,"plugins":[${entries.joinToString(",")}]}""".toByteArray())

    private fun entry(id: String, version: String? = null, packageUrl: String? = null): String {
        val fields = mutableListOf(
            "\"id\":\"$id\"",
            "\"name\":\"Plugin\"",
            "\"description\":\"Description\"",
            "\"author\":\"community\"",
            "\"repository\":\"https://github.com/community/plugins\""
        )
        version?.let { fields += "\"version\":\"$it\"" }
        packageUrl?.let { fields += "\"package_url\":\"$it\"" }
        return "{${fields.joinToString(",")}}"
    }

    private companion object {
        const val PACKAGE_URL = "https://raw.githubusercontent.com/xzyy03/Muse-Plugins-Store/main/packages/community.demo-kit_1.2.0.zip"
    }
}
