CREATE TABLE IF NOT EXISTS memory_records (
    id TEXT NOT NULL,
    markdownPath TEXT,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    scope TEXT NOT NULL,
    kind TEXT NOT NULL,
    source TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    hitCount INTEGER NOT NULL,
    lastHitAt INTEGER NOT NULL,
    pinned INTEGER NOT NULL,
    PRIMARY KEY(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS index_memory_records_markdownPath
    ON memory_records(markdownPath);
CREATE INDEX IF NOT EXISTS index_memory_records_scope_name
    ON memory_records(scope, name);

CREATE TABLE IF NOT EXISTS memory_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    memoryId TEXT NOT NULL,
    content TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    kind TEXT NOT NULL,
    source TEXT NOT NULL,
    pinned INTEGER NOT NULL,
    reason TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    FOREIGN KEY(memoryId) REFERENCES memory_records(id) ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_memory_versions_memoryId_createdAt
    ON memory_versions(memoryId, createdAt);
CREATE UNIQUE INDEX IF NOT EXISTS index_memory_versions_memoryId_id
    ON memory_versions(memoryId, id);

CREATE TABLE IF NOT EXISTS memory_sources (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    memoryId TEXT NOT NULL,
    versionId INTEGER NOT NULL,
    messageId TEXT,
    evidenceType TEXT NOT NULL,
    position INTEGER,
    messageHash TEXT NOT NULL,
    status TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    FOREIGN KEY(memoryId) REFERENCES memory_records(id) ON UPDATE NO ACTION ON DELETE CASCADE,
    FOREIGN KEY(memoryId, versionId) REFERENCES memory_versions(memoryId, id) ON UPDATE NO ACTION ON DELETE CASCADE,
    FOREIGN KEY(messageId) REFERENCES agent_messages(id) ON UPDATE NO ACTION ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS index_memory_sources_memoryId_versionId ON memory_sources(memoryId, versionId);
CREATE INDEX IF NOT EXISTS index_memory_sources_versionId ON memory_sources(versionId);
CREATE INDEX IF NOT EXISTS index_memory_sources_messageId ON memory_sources(messageId);
