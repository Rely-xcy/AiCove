package com.aicove.feature.plugin.presentation

import androidx.lifecycle.viewModelScope
import com.aicove.core.util.FileLogger
import com.aicove.feature.plugin.data.PluginRecord
import com.aicove.feature.plugin.data.index.PluginIndexClient
import com.aicove.feature.plugin.data.index.PluginIndexDocument
import com.aicove.feature.plugin.data.index.PluginIndexPlugin
import com.aicove.feature.plugin.data.index.PluginIndexSelection
import com.aicove.feature.plugin.data.index.PluginIndexSnapshot
import com.aicove.feature.plugin.data.index.PluginIndexSource
import com.aicove.feature.plugin.data.index.PluginIndexSourceRepository
import com.aicove.feature.plugin.domain.InstalledPlugin
import com.aicove.feature.plugin.domain.LocalizedText
import com.aicove.feature.plugin.domain.PluginManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginStoreViewModelTest {
    private val first = PluginIndexSource("first", "First", "https://example.com/first/index.json", false)
    private val second = PluginIndexSource("second", "Second", "https://example.com/second/index.json", false)
    private val selection = MutableStateFlow(PluginIndexSelection(listOf(first, second), first.id))
    private val installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    private val repository = mockk<PluginIndexSourceRepository>()
    private val client = mockk<PluginIndexClient>()
    private val manager = mockk<PluginManager>()
    private val pending = mutableListOf<CompletableDeferred<PluginIndexSnapshot>>()
    private lateinit var viewModel: PluginStoreViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } just runs
        every { repository.selection } returns selection
        every { manager.plugins } returns installed
        every { client.isCompatible(any()) } returns true
        coEvery { repository.markFetched(any(), any()) } just runs
        coEvery { repository.selectSource(any()) } coAnswers {
            selection.value = selection.value.copy(selectedSourceId = firstArg())
        }
    }

    @After
    fun tearDown() {
        try {
            pending.forEach { it.complete(snapshot("cleanup")) }
            if (::viewModel.isInitialized) runBlocking {
                withTimeout(3_000) {
                    checkNotNull(viewModel.viewModelScope.coroutineContext[Job]).cancelAndJoin()
                }
            }
        } finally {
            Dispatchers.resetMain()
            unmockkObject(FileLogger)
        }
    }

    @Test
    fun switchingSource_cancelsOldLoadAndIgnoresItsLateResult() = runBlocking {
        val oldResult = pendingResult()
        var oldJob: Job? = null
        coEvery { client.fetchIndex(first, false) } coAnswers {
            oldJob = currentCoroutineContext()[Job]
            withContext(NonCancellable) { oldResult.await() }
        }
        coEvery { client.fetchIndex(second, false) } returns snapshot("second")
        start()

        assertTrue(viewModel.selectSource(second.id).isSuccess)
        assertTrue(checkNotNull(oldJob).isCancelled)
        oldResult.complete(snapshot("first", rejected = 7))

        assertEquals(second.id, viewModel.state.value.selectedSourceId)
        assertEquals(listOf("second"), viewModel.state.value.items.map { it.plugin.id })
        assertEquals(0, viewModel.state.value.rejectedPluginCount)
        coVerify(exactly = 0) { repository.markFetched(first.id, any()) }
    }

    @Test
    fun switchingSource_clearsOldItemsErrorsAndRejectedCountBeforeNewLoad() = runBlocking {
        val newResult = pendingResult()
        coEvery { client.fetchIndex(first, false) } returns snapshot("first", rejected = 3)
        coEvery { client.fetchIndex(first, true) } throws IOException("offline")
        coEvery { client.fetchIndex(second, false) } coAnswers { newResult.await() }
        start()
        viewModel.refresh()
        assertEquals(PluginStoreErrorCode.NETWORK, viewModel.state.value.error?.code)
        assertEquals(3, viewModel.state.value.rejectedPluginCount)

        viewModel.selectSource(second.id)

        val state = viewModel.state.value
        assertTrue(state.loading)
        assertTrue(state.items.isEmpty())
        assertTrue(state.updatesByPluginId.isEmpty())
        assertNull(state.error)
        assertEquals(0, state.rejectedPluginCount)
        newResult.complete(snapshot("second"))
    }

    @Test
    fun fetchedTimestampChange_doesNotReloadIndex() {
        coEvery { client.fetchIndex(first, false) } returns snapshot("first")
        coEvery { repository.markFetched(first.id, any()) } coAnswers {
            selection.value = selection.value.copy(sources = listOf(first.copy(lastFetchedAt = 42), second))
        }
        start()

        assertEquals(42L, viewModel.state.value.sources.first().lastFetchedAt)
        assertEquals(listOf("first"), viewModel.state.value.items.map { it.plugin.id })
        coVerify(exactly = 1) { client.fetchIndex(any(), any()) }
    }

    @Test
    fun sameAddressDifferentSource_doesNotReusePendingRefresh() = runBlocking {
        val sameAddress = second.copy(indexUrl = first.indexUrl)
        selection.value = selection.value.copy(sources = listOf(first, sameAddress))
        val oldRefresh = pendingResult()
        coEvery { client.fetchIndex(first, false) } returns snapshot("first")
        coEvery { client.fetchIndex(first, true) } coAnswers {
            withContext(NonCancellable) { oldRefresh.await() }
        }
        coEvery { client.fetchIndex(sameAddress, false) } returns snapshot("second")
        start()
        viewModel.refresh()

        viewModel.selectSource(second.id)
        oldRefresh.complete(snapshot("late-first"))

        assertEquals(second.id, viewModel.state.value.items.single().source.id)
        assertEquals("second", viewModel.state.value.items.single().plugin.id)
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun sameSourceOfflineRefresh_preservesVisibleItemsAndRejectedCount() {
        coEvery { client.fetchIndex(first, false) } returns snapshot("first", rejected = 2)
        coEvery { client.fetchIndex(first, true) } throws IOException("offline")
        start()

        viewModel.refresh()

        assertEquals(listOf("first"), viewModel.state.value.items.map { it.plugin.id })
        assertEquals(2, viewModel.state.value.rejectedPluginCount)
        assertEquals(PluginStoreErrorCode.NETWORK, viewModel.state.value.error?.code)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun updatesOnlyMatchInstalledSource_evenWhenAnotherSourceHasNewerVersion() = runBlocking {
        val record = PluginRecord(
            id = "shared.plugin", version = "1.0.0", enabled = true, installedAt = 0,
            origin = "index", archiveSha256 = "", manifestSha256 = "",
            sourceId = first.id, indexUrl = first.indexUrl
        )
        val plugin = mockk<InstalledPlugin>()
        every { plugin.id } returns record.id
        every { plugin.record } returns record
        installed.value = listOf(plugin)
        coEvery { client.fetchIndex(first, false) } returns snapshot(record.id, version = "2.0.0")
        coEvery { client.fetchIndex(second, false) } returns snapshot(record.id, version = "3.0.0")
        start()
        assertTrue(viewModel.state.value.items.single().updateAvailable)

        viewModel.selectSource(second.id)

        assertFalse(viewModel.state.value.items.single().updateAvailable)
        assertTrue(viewModel.state.value.updatesByPluginId.isEmpty())
    }

    @Test
    fun invalidCustomSources_returnsVisibleFailureWithoutChangingSelection() = runBlocking {
        coEvery { client.fetchIndex(first, false) } returns snapshot("first")
        coEvery { repository.setCustomSources(listOf("invalid")) } returns
            Result.failure(IllegalArgumentException("Invalid index URL"))
        start()

        assertTrue(viewModel.saveCustomSources("invalid").isFailure)
        assertEquals(first.id, viewModel.state.value.selectedSourceId)
        assertEquals("first", viewModel.state.value.items.single().plugin.id)
    }

    private fun start() {
        viewModel = PluginStoreViewModel(client, repository, manager, mockk())
    }

    private fun pendingResult() = CompletableDeferred<PluginIndexSnapshot>().also { pending += it }

    private fun snapshot(id: String, rejected: Int = 0, version: String = "1.0.0") = PluginIndexSnapshot(
        PluginIndexDocument(
            schemaVersion = 1,
            plugins = listOf(
                PluginIndexPlugin(
                    id = id,
                    name = LocalizedText.single(id),
                    description = LocalizedText.single(id),
                    author = "author",
                    repository = "https://github.com/example/plugins",
                    version = version
                )
            ),
            rejectedPluginCount = rejected
        ),
        stale = false,
        fetchedAt = 42
    )
}
