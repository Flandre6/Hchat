CREATE TABLE plugin_version_files (
    version_id TEXT NOT NULL REFERENCES plugin_versions(id) ON DELETE CASCADE,
    name TEXT NOT NULL COLLATE NOCASE,
    encoding TEXT NOT NULL CHECK (encoding IN ('utf-8', 'base64')),
    content BLOB NOT NULL,
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
    size INTEGER NOT NULL CHECK (size >= 0 AND size <= 8388608),
    PRIMARY KEY (version_id, name)
);

CREATE INDEX idx_plugin_version_files_version
    ON plugin_version_files(version_id, name COLLATE BINARY);
