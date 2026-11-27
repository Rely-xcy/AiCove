CREATE TABLE IF NOT EXISTS subagent_runs (
    subSessionId TEXT NOT NULL,
    parentSessionId TEXT NOT NULL,
    status TEXT NOT NULL,
    startedAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    terminationReason TEXT,
    claimedPrompt TEXT,
    initialPrompt TEXT,
    PRIMARY KEY(subSessionId),
    FOREIGN KEY(subSessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
    FOREIGN KEY(parentSessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_subagent_runs_parentSessionId_status
    ON subagent_runs(parentSessionId, status);
