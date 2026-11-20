package com.aicove.feature.settings.data.remote

import com.aicove.feature.settings.data.repository.UpdateChannel
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckServiceTest {
    @Test
    fun onlyPrimaryErrorsCallBackup() = runBlocking {
        var backupCalls = 0
        val backup: suspend () -> UpdateCheckResult = {
            backupCalls++
            UpdateCheckResult.UpToDate
        }
        assertSame(UpdateCheckResult.UpToDate, checkUpdateSources({ UpdateCheckResult.UpToDate }, backup))
        val update = UpdateCheckResult.NewVersion(UpdateInfo("v2.0.0", "changes", emptyList()))
        assertSame(update, checkUpdateSources({ update }, backup))
        assertEquals(0, backupCalls)
        assertSame(UpdateCheckResult.UpToDate, checkUpdateSources({ UpdateCheckResult.Error("HTTP 403") }, backup))
        assertEquals(1, backupCalls)
    }

    @Test
    fun primaryCancellationPropagatesWithoutCallingBackup() = runBlocking {
        var backupCalls = 0
        val cancellation = CancellationException("GitHub check cancelled")
        val error = runCatching {
            checkUpdateSources(
                primary = { throw cancellation },
                backup = { backupCalls++; UpdateCheckResult.UpToDate }
            )
        }.exceptionOrNull()
        assertSame(cancellation, error)
        assertEquals(0, backupCalls)
    }

    @Test
    fun cancelledPrimaryReturningErrorDoesNotStartBackup() = runBlocking {
        var backupCalls = 0
        val error = runCatching {
            coroutineScope {
                checkUpdateSources(
                    primary = {
                        currentCoroutineContext().cancel(CancellationException("GitHub check cancelled"))
                        UpdateCheckResult.Error("request interrupted")
                    },
                    backup = { backupCalls++; UpdateCheckResult.UpToDate }
                )
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, backupCalls)
    }

    @Test
    fun stableHistoryExcludesDraftsPrereleasesAndOlderVersions() {
        val releases = JsonParser.parseString(
            """[
                {"tag_name":"v1.2.0","body":"second","assets":[]},
                {"tag_name":"v1.4.0-rc1","prerelease":true,"body":"RC","assets":[]},
                {"tag_name":"v1.5.0","draft":true,"body":"draft","assets":[]},
                {"tag_name":"v1.3.0","body":"## Third\n**Full** log","assets":[
                    {"name":"aicove-arm64-v1.3.0.apk","browser_download_url":"https://example.test/arm64.apk","size":42,
                        "digest":"sha256:${"a".repeat(64)}"},
                    {"name":"aicove-universal-v1.3.0.apk","browser_download_url":"https://example.test/universal.apk"}
                ]},
                {"tag_name":"v1.1.0","body":"installed","assets":[]},
                {"tag_name":"manual-40","body":"not a version","assets":[]}
            ]"""
        ).asJsonArray
        val result = parseGitHubUpdates(releases, "1.1.0", UpdateChannel.STABLE, "arm64", "empty")!!
        assertEquals(listOf("v1.3.0", "v1.2.0"), result.updates.map { it.tag })
        assertEquals("Third\nFull log", result.updates.first().changelog)
        assertEquals("https://example.test/arm64.apk", result.latestAsset?.url)
        assertEquals("a".repeat(64), result.latestAsset?.sha256)
        assertEquals(42L, result.latestAsset?.size)
        assertFalse(result.changelog.contains("installed"))
        assertNull(result.downloadUrl)
    }

    @Test
    fun releaseNotesRemainAvailableWhenTheApkIsForAnotherPackage() {
        val releases = JsonParser.parseString(
            """[{"tag_name":"v1.3.0","body":"stable notes","assets":[
              {"name":"aicove-universal-v1.3.0.apk","browser_download_url":"https://example.test/universal.apk"}
            ]}]"""
        ).asJsonArray
        val update = parseGitHubUpdates(releases, "1.2.0", UpdateChannel.BETA, null, "empty", includeInstallAsset = false)!!
        assertEquals("v1.3.0", update.latestTag)
        assertTrue(update.changelog.contains("stable notes"))
        assertNull(update.latestAsset)
    }

    @Test
    fun prereleaseChannelIncludesNewerReleaseWithoutOfferingDowngrade() {
        val releases = JsonParser.parseString(
            """[
                {"tag_name":"v1.4.0-rc1","prerelease":true,"body":"RC","assets":[]},
                {"tag_name":"v1.3.0","body":"stable","assets":[]}
            ]"""
        ).asJsonArray
        val update = parseGitHubUpdates(releases, "1.3.0", UpdateChannel.BETA, null, "empty")!!
        assertEquals("v1.4.0-rc1", update.latestTag)
        assertTrue(update.changelog.contains("RC"))
        assertNull(parseGitHubUpdates(releases, "1.5.0-dev.10", UpdateChannel.BETA, null, "empty"))
        assertNull(parseGitHubUpdates(releases, "1.3.0", UpdateChannel.STABLE, null, "empty"))
    }
}
