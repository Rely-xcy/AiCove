CREATE TABLE IF NOT EXISTS task_states (
    id TEXT NOT NULL,
    projectKey TEXT NOT NULL,
    goal TEXT NOT NULL,
    status TEXT NOT NULL,
    completedSteps TEXT NOT NULL,
    pendingSteps TEXT NOT NULL,
    blockedSteps TEXT NOT NULL,
    constraints TEXT NOT NULL,
    lastSessionId TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY(id)
);
CREATE INDEX IF NOT EXISTS index_task_states_projectKey_status_updatedAt
    ON task_states(projectKey, status, updatedAt);

CREATE TABLE IF NOT EXISTS task_evidence (
    id TEXT NOT NULL,
    taskId TEXT NOT NULL,
    sourceMessageId TEXT,
    sourceSessionId TEXT NOT NULL,
    evidenceType TEXT NOT NULL,
    verificationStatus TEXT NOT NULL,
    sourceHash TEXT NOT NULL,
    sourceStatus TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    PRIMARY KEY(id),
    FOREIGN KEY(taskId) REFERENCES task_states(id) ON UPDATE NO ACTION ON DELETE CASCADE,
    FOREIGN KEY(sourceMessageId) REFERENCES agent_messages(id) ON UPDATE NO ACTION ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS index_task_evidence_taskId_verificationStatus
    ON task_evidence(taskId, verificationStatus);
CREATE INDEX IF NOT EXISTS index_task_evidence_sourceMessageId
    ON task_evidence(sourceMessageId);
