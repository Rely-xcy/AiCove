package com.aicove.feature.settings.data.remote

import com.aicove.feature.settings.data.repository.UpdateChannel
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkUpdateCheckServiceTest {
    @Test
    fun serverDecisionRemainsAuthoritative() {
        assertFalse(shouldReportUpdate(released = false, hasUpdate = true, direction = "up"))
        assertFalse(shouldReportUpdate(released = true, hasUpdate = false, direction = "same"))
        assertFalse(shouldReportUpdate(released = true, hasUpdate = true, direction = "down"))
        assertFalse(shouldReportUpdate(released = true, hasUpdate = false, direction = ""))
        assertTrue(shouldReportUpdate(released = true, hasUpdate = true, direction = "up"))
    }

    @Test
    fun legacyResponseKeepsFullNoteAndLatestAsset() {
        val obj = JsonParser.parseString(
            """{"versionName":"1.3.0","versionCode":30,"note":"full log","noteShort":"preview",
                "name":"latest.apk","path":"/dl/file/latest","sha256":"${"a".repeat(64)}","size":99}"""
        ).asJsonObject
        val note = (1..40).joinToString("\n") { "line$it" }
        obj.addProperty("note", note)
        val result = parseForkUpdateInfo(obj, 10, UpdateChannel.BETA, "empty")
        assertEquals(1, result.updates.size)
        assertEquals(note, result.updates.single().changelog)
        assertTrue(result.changelog.contains("line40"))
        assertEquals("https://rely-xfer.de5.net/dl/file/latest?dl=1", result.latestAsset?.url)
        assertEquals("a".repeat(64), result.latestAsset?.sha256)
        assertEquals(99L, result.latestAsset?.size)
    }

    @Test
    fun historyIsFilteredSortedDeduplicatedAndNeverSuppliesDownloadTarget() {
        val obj = JsonParser.parseString(
            """{
                "versionName":"1.4.0","versionCode":40,"note":"latest","url":"https://example.test/latest.apk",
                "releases":[
                    {"versionCode":20,"versionName":"1.2.0","channel":"beta","note":"second"},
                    {"versionCode":30,"versionName":"1.3.0","channel":"beta","note":"third","url":"https://example.test/wrong.apk"},
                    {"versionCode":30,"versionName":"duplicate","channel":"beta"},
                    {"versionCode":40,"versionName":"duplicate latest","channel":"beta"},
                    {"versionCode":50,"versionName":"future","channel":"beta"},
                    {"versionCode":25,"versionName":"other channel","channel":"stable"},
                    {"versionCode":10,"versionName":"installed","channel":"beta"},
                    {"versionName":"unknown","channel":"beta"}
                ]
            }"""
        ).asJsonObject
        val result = parseForkUpdateInfo(obj, 10, UpdateChannel.BETA, "empty")
        assertEquals(listOf("1.4.0", "1.3.0", "1.2.0"), result.updates.map { it.tag })
        assertEquals("https://example.test/latest.apk", result.latestAsset?.url)
        assertEquals(result.latestAsset?.url, result.downloadUrl)
        assertTrue(result.updates.drop(1).all { it.asset == null })
        assertFalse(result.changelog.contains("future"))
    }

    @Test
    fun missingLatestUrlNeverUsesAnOlderDownload() {
        val obj = JsonParser.parseString(
            """{"versionCode":30,"title":"latest","releases":[
                {"versionCode":20,"channel":"beta","url":"https://example.test/old.apk"}
            ]}"""
        ).asJsonObject
        val result = parseForkUpdateInfo(obj, 10, UpdateChannel.BETA, "empty")
        assertNull(result.latestAsset)
        assertNull(result.downloadUrl)
    }
}
