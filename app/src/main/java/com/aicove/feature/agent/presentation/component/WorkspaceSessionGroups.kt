package com.aicove.feature.agent.presentation.component

import com.aicove.feature.agent.domain.model.ChatSession

internal data class WorkspaceSessionGroup(
    val workspacePath: String,
    val sessions: List<ChatSession>,
    val groups: List<SessionGroup>
)

internal fun buildWorkspaceSessionGroups(
    sessions: List<ChatSession>,
    currentWorkspacePath: String?,
    now: Long
): List<WorkspaceSessionGroup> = sessions.groupBy { it.workspacePath }
    .map { (path, workspaceSessions) ->
        val ordered = workspaceSessions.sortedWith(
            compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt }
        )
        val pinned = ordered.filter { it.isPinned }
        val groups = buildList {
            if (pinned.isNotEmpty()) add(SessionGroup("pinned", pinned))
            addAll(buildSessionGroups(ordered.filterNot { it.isPinned }, now))
        }
        WorkspaceSessionGroup(path, ordered, groups)
    }
    .sortedWith(
        compareByDescending<WorkspaceSessionGroup> { it.workspacePath == currentWorkspacePath }
            .thenByDescending { it.sessions.maxOf { session -> session.updatedAt } }
            .thenBy { it.workspacePath }
    )
