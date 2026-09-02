CREATE TABLE plugin_versions_v2 (
    id TEXT PRIMARY KEY,
    plugin_id TEXT NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    version_name TEXT NOT NULL,
    release_notes TEXT NOT NULL DEFAULT '' CHECK (length(release_notes) <= 500),
    content_hash TEXT NOT NULL,
    total_size INTEGER NOT NULL CHECK (total_size >= 0 AND total_size <= 20971520),
    created_at TEXT NOT NULL,
    main_content TEXT NOT NULL,
    main_sha256 TEXT NOT NULL,
    main_size INTEGER NOT NULL CHECK (main_size >= 0 AND main_size <= 524288),
    bshs_content BLOB,
    bshs_sha256 TEXT,
    bshs_size INTEGER,
    info_content TEXT,
    info_sha256 TEXT,
    info_size INTEGER CHECK (info_size IS NULL OR (info_size >= 0 AND info_size <= 65536)),
    readme_content TEXT,
    readme_sha256 TEXT,
    readme_size INTEGER CHECK (readme_size IS NULL OR (readme_size >= 0 AND readme_size <= 262144)),
    CHECK (
        (bshs_content IS NULL AND bshs_sha256 IS NULL AND bshs_size IS NULL)
        OR (
            typeof(bshs_content) = 'blob'
            AND length(bshs_content) = bshs_size
            AND bshs_size >= 0
            AND bshs_size <= 16777216
            AND length(bshs_sha256) = 64
        )
    )
);

INSERT INTO plugin_versions_v2(
    id, plugin_id, version_name, content_hash, total_size, created_at,
    main_content, main_sha256, main_size,
    info_content, info_sha256, info_size,
    readme_content, readme_sha256, readme_size
)
SELECT
    id, plugin_id, version_name, content_hash, total_size, created_at,
    main_content, main_sha256, main_size,
    info_content, info_sha256, info_size,
    readme_content, readme_sha256, readme_size
FROM plugin_versions;

DROP TABLE plugin_versions;
ALTER TABLE plugin_versions_v2 RENAME TO plugin_versions;
CREATE INDEX idx_plugin_versions_plugin ON plugin_versions(plugin_id, created_at DESC);
