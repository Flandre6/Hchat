CREATE TABLE plugins (
    id TEXT PRIMARY KEY,
    source_plugin_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    author TEXT NOT NULL DEFAULT '',
    owner_token_hash TEXT NOT NULL,
    uploader_key_hash TEXT NOT NULL,
    latest_version_id TEXT NOT NULL,
    download_count INTEGER NOT NULL DEFAULT 0 CHECK (download_count >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE plugin_versions (
    id TEXT PRIMARY KEY,
    plugin_id TEXT NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    version_name TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    total_size INTEGER NOT NULL CHECK (total_size >= 0 AND total_size <= 1048576),
    created_at TEXT NOT NULL,
    main_content TEXT NOT NULL,
    main_sha256 TEXT NOT NULL,
    main_size INTEGER NOT NULL CHECK (main_size >= 0 AND main_size <= 524288),
    info_content TEXT,
    info_sha256 TEXT,
    info_size INTEGER CHECK (info_size IS NULL OR (info_size >= 0 AND info_size <= 65536)),
    readme_content TEXT,
    readme_sha256 TEXT,
    readme_size INTEGER CHECK (readme_size IS NULL OR (readme_size >= 0 AND readme_size <= 262144))
);

CREATE INDEX idx_plugins_latest ON plugins(updated_at DESC, id DESC);
CREATE INDEX idx_plugins_downloads ON plugins(download_count DESC, updated_at DESC);
CREATE INDEX idx_plugins_source ON plugins(source_plugin_id);
CREATE INDEX idx_plugin_versions_plugin ON plugin_versions(plugin_id, created_at DESC);

CREATE TABLE upload_rate_limits (
    quota_key TEXT PRIMARY KEY,
    window_start INTEGER NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count >= 0),
    updated_at INTEGER NOT NULL
);

CREATE INDEX idx_upload_rate_limits_window ON upload_rate_limits(window_start);
