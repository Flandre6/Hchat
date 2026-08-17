-- Some older deployments recorded migration 004 without creating the table.
-- Create the new-schema source table in that case; otherwise this is a no-op.
CREATE TABLE IF NOT EXISTS plugin_version_files (
    version_id TEXT NOT NULL REFERENCES plugin_versions(id) ON DELETE CASCADE,
    name TEXT NOT NULL COLLATE NOCASE,
    encoding TEXT NOT NULL CHECK (encoding IN ('utf-8', 'base64')),
    content BLOB NOT NULL,
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
    size INTEGER NOT NULL CHECK (size >= 0 AND size <= 16777216),
    PRIMARY KEY (version_id, name)
);

CREATE TABLE plugin_version_files_new (
    version_id TEXT NOT NULL REFERENCES plugin_versions(id) ON DELETE CASCADE,
    name TEXT NOT NULL COLLATE NOCASE,
    encoding TEXT NOT NULL CHECK (encoding IN ('utf-8', 'base64')),
    content BLOB NOT NULL,
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
    size INTEGER NOT NULL CHECK (size >= 0 AND size <= 16777216),
    PRIMARY KEY (version_id, name)
);

INSERT INTO plugin_version_files_new(version_id, name, encoding, content, sha256, size)
SELECT version_id, name, encoding, content, sha256, size
FROM plugin_version_files;

DROP TABLE plugin_version_files;
ALTER TABLE plugin_version_files_new RENAME TO plugin_version_files;

CREATE INDEX idx_plugin_version_files_version
    ON plugin_version_files(version_id, name COLLATE BINARY);
