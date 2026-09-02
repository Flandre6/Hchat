CREATE TABLE plugin_likes (
    plugin_id TEXT NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    user_wxid TEXT NOT NULL CHECK (length(user_wxid) BETWEEN 1 AND 128),
    user_wechat_id TEXT NOT NULL DEFAULT '' CHECK (length(user_wechat_id) <= 128),
    user_nickname TEXT NOT NULL DEFAULT '' CHECK (length(user_nickname) <= 100),
    actor_key_hash TEXT NOT NULL CHECK (length(actor_key_hash) = 64),
    created_at TEXT NOT NULL,
    PRIMARY KEY (plugin_id, user_wxid)
);

CREATE INDEX idx_plugin_likes_created
    ON plugin_likes(plugin_id, created_at DESC, user_wxid ASC);

CREATE TABLE plugin_comments (
    id TEXT PRIMARY KEY,
    plugin_id TEXT NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    user_wxid TEXT NOT NULL CHECK (length(user_wxid) BETWEEN 1 AND 128),
    user_wechat_id TEXT NOT NULL DEFAULT '' CHECK (length(user_wechat_id) <= 128),
    user_nickname TEXT NOT NULL DEFAULT '' CHECK (length(user_nickname) <= 100),
    actor_key_hash TEXT NOT NULL CHECK (length(actor_key_hash) = 64),
    content TEXT NOT NULL CHECK (length(content) BETWEEN 1 AND 1000),
    created_at TEXT NOT NULL
);

CREATE INDEX idx_plugin_comments_plugin
    ON plugin_comments(plugin_id, created_at DESC, id DESC);
