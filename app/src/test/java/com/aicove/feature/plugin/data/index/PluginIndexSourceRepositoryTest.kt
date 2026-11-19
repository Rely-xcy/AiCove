package com.aicove.feature.plugin.data.index

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIndexSourceRepositoryTest {
    @Test
    fun sources_restoreOldSettings_switchPersistently_andValidateEditsAtomically() = runBlocking {
        val context = mockk<Context>()
        val directory = Files.createTempDirectory("plugin-index-sources").toFile()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory
        val seedJob = Job()
        val seedStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(seedJob + Dispatchers.IO)) {
            File(directory, "datastore/plugin_index_sources.preferences_pb")
        }
        try {
            seedStore.edit {
                it[stringPreferencesKey("sources_json")] =
                    """{"sources":[{"id":"aicode.official","name":"AiCove Plugins","indexUrl":"https://raw.githubusercontent.com/jieapi/AiCode-Plugins-Store/main/index.json","lastFetchedAt":12},{"id":"custom","name":"Community","indexUrl":"https://example.com/index.json"}]}"""
            }
        } finally {
            seedJob.cancelAndJoin()
        }
        val repository = PluginIndexSourceRepository(context)
        val migrated = repository.selection.first()
        assertEquals(PluginIndexDefaults.ALL_SOURCES_ID, migrated.selectedSourceId)
        assertEquals(4, migrated.sources.size)
        assertEquals(12L, migrated.sources.single { it.id == "aicode.official" }.lastFetchedAt)
        assertFalse(migrated.sources.single { it.id == "aicode.official" }.official)
        assertEquals(listOf("https://example.com/index.json"), repository.customIndexUrls.first())

        repository.selectSource("muse.store")
        repository.markFetched("muse.store", 123L)
        val restored = PluginIndexSourceRepository(context).selection.first()
        assertEquals("muse.store", restored.selectedSourceId)
        assertEquals(123L, restored.sources.single { it.id == "muse.store" }.lastFetchedAt)

        assertTrue(repository.setCustomSources(listOf("https://other.example/index.json", "file:///bad")).isFailure)
        assertEquals(listOf("https://example.com/index.json"), repository.customIndexUrls.first())
        assertTrue(repository.setCustomSources(listOf(
            "https://github.com/example/store",
            "https://raw.githubusercontent.com/example/store/main/index.json",
            "https://github.com/xzyy03/Muse-Plugins-Store",
            ""
        )).isSuccess)
        val custom = repository.selection.first().sources.last()
        assertEquals(4, repository.selection.first().sources.size)
        repository.selectSource(custom.id)
        repository.markFetched("muse.store", 124L)
        assertEquals(custom.id, repository.selection.first().selectedSourceId)
        assertTrue(repository.setCustomSources(emptyList()).isSuccess)
        assertEquals(PluginIndexDefaults.ALL_SOURCES_ID, repository.selection.first().selectedSourceId)
        assertEquals(3, repository.selection.first().sources.size)
        repository.selectSource(PluginIndexDefaults.ALL_SOURCES_ID)
        assertTrue(repository.setCustomSources(listOf("https://example.com/another-index.json")).isSuccess)
        assertEquals(PluginIndexDefaults.ALL_SOURCES_ID, PluginIndexSourceRepository(context).selection.first().selectedSourceId)
    }

    @Test
    fun githubFileAndBranchUrls_resolveToIndex() {
        assertEquals("https://raw.githubusercontent.com/example/store/dev/index.json",
            PluginIndexSourceRepository.normalizeIndexUrl("https://github.com/example/store/tree/dev"))
        assertEquals("https://raw.githubusercontent.com/example/store/dev/catalog/index.json",
            PluginIndexSourceRepository.normalizeIndexUrl("https://github.com/example/store/blob/dev/catalog/index.json"))
        assertEquals("https://raw.githubusercontent.com/example/store/main/my%20index%23%25.json",
            PluginIndexSourceRepository.normalizeIndexUrl("https://github.com/example/store/blob/main/my%20index%23%25.json"))
        assertEquals("https://example.com/index.json",
            PluginIndexSourceRepository.normalizeIndexUrl(" https://example.com/index.json "))
        listOf("https://user:secret@example.com/index.json", "https://example.com/index.json#fragment",
            "https://github.com/example/store/issues").forEach {
            assertTrue(runCatching { PluginIndexSourceRepository.normalizeIndexUrl(it) }.isFailure)
        }
    }

    @Test
    fun officialBadge_identifiesAiCoveRepositoryOwner() {
        assertTrue(PluginOfficialRepositories.isOfficial("https://github.com/Rely-xcy/AiCove"))
        assertFalse(PluginOfficialRepositories.isOfficial("https://github.com/jieapi/aicode"))
        assertFalse(PluginOfficialRepositories.isOfficial("https://github.com/xzyy03/Muse"))
    }
}
