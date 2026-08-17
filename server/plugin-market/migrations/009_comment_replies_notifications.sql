ALTER TABLE plugin_comments
    ADD COLUMN parent_comment_id TEXT REFERENCES plugin_comments(id) ON DELETE CASCADE;

CREATE INDEX idx_plugin_comments_parent
    ON plugin_comments(parent_comment_id, created_at ASC, id ASC);

CREATE TABLE plugin_notifications (
    id TEXT PRIMARY KEY,
    recipient_actor_key_hash TEXT NOT NULL CHECK (length(recipient_actor_key_hash) = 64),
    plugin_id TEXT NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    comment_id TEXT NOT NULL REFERENCES plugin_comments(id) ON DELETE CASCADE,
    parent_comment_id TEXT NOT NULL REFERENCES plugin_comments(id) ON DELETE CASCADE,
    actor_nickname TEXT NOT NULL DEFAULT '' CHECK (length(actor_nickname) <= 100),
    content TEXT NOT NULL CHECK (length(content) BETWEEN 1 AND 1000),
    created_at TEXT NOT NULL,
    read_at TEXT
);

CREATE INDEX idx_plugin_notifications_recipient
    ON plugin_notifications(recipient_actor_key_hash, created_at DESC, id DESC);

CREATE INDEX idx_plugin_notifications_unread
    ON plugin_notifications(recipient_actor_key_hash, read_at, created_at DESC);
