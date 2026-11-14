package com.aicove.feature.agent.presentation.component

import com.aicove.feature.agent.domain.model.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceSessionGroupsTest {
    private val now = 1_798_000_000_000L

    @Test
    fun `each workspace keeps its own sessions and current workspace comes first`() {
        val sessions = listOf(
            session("b-new", "/projects/b", now),
            session("a-old", "/projects/a", now - 60_000),
            session("b-old", "/projects/b", now - 120_000)
        )

        val groups = buildWorkspaceSessionGroups(sessions, "/projects/a", now)

        assertEquals(listOf("/projects/a", "/projects/b"), groups.map { it.workspacePath })
        assertEquals(listOf("a-old"), groups[0].sessions.map { it.id })
        assertEquals(listOf("b-new", "b-old"), groups[1].sessions.map { it.id })
    }

    @Test
    fun `pinned sessions remain inside their workspace`() {
        val sessions = listOf(
            session("a-new", "/projects/a", now),
            session("b-pinned", "/projects/b", now - 120_000, pinned = true),
            session("b-new", "/projects/b", now),
            session("b-pinned-new", "/projects/b", now - 60_000, pinned = true)
        )

        val group = buildWorkspaceSessionGroups(sessions, "/projects/a", now)
            .first { it.workspacePath == "/projects/b" }

        assertEquals(listOf("pinned", "today"), group.groups.map { it.groupKey })
        assertEquals(listOf("b-pinned-new", "b-pinned"), group.groups[0].sessions.map { it.id })
        assertEquals(listOf("b-new"), group.groups[1].sessions.map { it.id })
    }

    @Test
    fun `deleted workspace and equal display names do not lose or merge history`() {
        val groups = buildWorkspaceSessionGroups(
            listOf(
                session("local", "/local/demo", now),
                session("remote", "/remote/demo", now),
                session("removed", "/removed/project", now)
            ),
            "/local/demo",
            now
        )

        assertEquals(3, groups.size)
        assertEquals(setOf("local", "remote", "removed"), groups.flatMap { it.sessions }.map { it.id }.toSet())
    }

    @Test
    fun `empty history has no workspace groups`() {
        assertEquals(emptyList<WorkspaceSessionGroup>(), buildWorkspaceSessionGroups(emptyList(), null, now))
    }

    private fun session(id: String, path: String, time: Long, pinned: Boolean = false) = ChatSession(
        id = id,
        title = id,
        createdAt = time,
        updatedAt = time,
        workspacePath = path,
        isPinned = pinned
    )
}
