CREATE TABLE IF NOT EXISTS agent_turn_runs (
    sessionId TEXT NOT NULL,
    userMessageId TEXT NOT NULL,
    status TEXT NOT NULL,
    startedAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY(sessionId),
    FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
    FOREIGN KEY(userMessageId) REFERENCES agent_messages(id) ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS index_agent_turn_runs_userMessageId
    ON agent_turn_runs(userMessageId);
