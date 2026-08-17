ALTER TABLE plugin_versions
    ADD COLUMN uploader_wxid TEXT NOT NULL DEFAULT '';

ALTER TABLE plugin_versions
    ADD COLUMN uploader_wechat_id TEXT NOT NULL DEFAULT '';

ALTER TABLE plugin_versions
    ADD COLUMN uploader_nickname TEXT NOT NULL DEFAULT '';

CREATE INDEX idx_plugin_versions_uploader_wxid
    ON plugin_versions(uploader_wxid, created_at DESC);

CREATE TABLE uploader_blacklist (
    wxid TEXT PRIMARY KEY,
    wechat_id TEXT NOT NULL DEFAULT '',
    nickname TEXT NOT NULL DEFAULT '',
    blacklisted_at TEXT NOT NULL
);

CREATE INDEX idx_uploader_blacklist_time
    ON uploader_blacklist(blacklisted_at DESC, wxid ASC);
