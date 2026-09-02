ALTER TABLE plugin_versions
    ADD COLUMN review_status TEXT NOT NULL DEFAULT 'approved'
    CHECK (review_status IN ('pending', 'approved'));

ALTER TABLE plugin_versions
    ADD COLUMN submitted_display_name TEXT NOT NULL DEFAULT '';

ALTER TABLE plugin_versions
    ADD COLUMN submitted_author TEXT NOT NULL DEFAULT '';

UPDATE plugin_versions
SET submitted_display_name = (
        SELECT p.display_name
        FROM plugins p
        WHERE p.id = plugin_versions.plugin_id
    ),
    submitted_author = (
        SELECT p.author
        FROM plugins p
        WHERE p.id = plugin_versions.plugin_id
    );

CREATE TABLE market_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    upload_review_enabled INTEGER NOT NULL DEFAULT 0 CHECK (upload_review_enabled IN (0, 1))
);

INSERT INTO market_settings(id, upload_review_enabled)
VALUES (1, 0);
