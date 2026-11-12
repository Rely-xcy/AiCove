package com.aicove.feature.agent.domain.subagent

import com.aicove.core.datastore.ListOrderStore
import com.aicove.feature.agent.domain.container.BulkFileSource
import com.aicove.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentDefinitionRepositoryContributionTest {

    private val global = mockk<GlobalDirectoryAgentSource>().apply {
        every { listDefinitions() } returns emptyList()
    }
    private val project = mockk<ProjectDirectoryAgentSource>().apply {
        every { listDefinitions() } returns emptyList()
    }
    private val config = mockk<AgentDefinitionConfigRepository>().apply {
        every { disabledNames() } returns emptySet()
    }
    private val contribution = mockk<AgentDefinitionContributionSource>().apply {
        every { listDefinitions() } returns emptyList()
        every { listAllDefinitions() } returns emptyList()
        every { revision } returns 0L
    }
    private val listOrderStore = mockk<ListOrderStore> {
        every { sort<AgentDefinitionEntry>(any(), any(), any()) } answers { firstArg() }
    }
    private val repository = AgentDefinitionRepository(
        global,
        project,
        config,
        TestFileAccessProvider(),
        listOrderStore,
        BulkFileSource.Disabled,
        setOf(contribution)
    )

    private fun agent(name: String) = AgentDefinition(name = name, description = "d", prompt = "p")

    @Test
    fun listEnabledDefinitions_appendsContributionsAndUserDefinitionWins() {
        val own = agent("Same")
        every { global.listDefinitions() } returns listOf(own)
        every { contribution.listDefinitions() } returns listOf(
            agent("z.kit:two"),
            agent("same"),
            agent("a.kit:one")
        )

        assertEquals(
            listOf("Same", "a.kit:one", "z.kit:two"),
            repository.listEnabledDefinitions().map { it.name }
        )
    }

    @Test
    fun findIncludingDisabled_reachesInactiveContribution() {
        every { contribution.listAllDefinitions() } returns listOf(agent("a.kit:one"))

        assertNull(repository.find("a.kit:one"))
        assertEquals("a.kit:one", repository.findIncludingDisabled("A.KIT:ONE")?.name)
    }

    @Test
    fun definitionsVersion_includesContributionRevision() {
        val before = repository.definitionsVersion
        every { contribution.revision } returns 7L

        assertEquals(before + 7L, repository.definitionsVersion)
    }
}
