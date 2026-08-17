CREATE TABLE plugin_download_events (
    event_id TEXT PRIMARY KEY,
    plugin_id TEXT NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    version_id TEXT NOT NULL REFERENCES plugin_versions(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_plugin_download_events_plugin
    ON plugin_download_events(plugin_id, created_at DESC);

-- Existing values counted detail-page views, so they cannot be converted into downloads.
UPDATE plugins SET download_count = 0;
