DROP INDEX IF EXISTS index_agent_messages_sessionId;
CREATE INDEX IF NOT EXISTS index_agent_messages_sessionId_timestamp ON agent_messages(sessionId, timestamp);
