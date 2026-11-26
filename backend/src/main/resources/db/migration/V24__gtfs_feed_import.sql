-- Each feed's last import, with a fingerprint of what it loaded: a download that is the same again is not imported.
CREATE TABLE gtfs_feed_import (
    feed             VARCHAR(32)  NOT NULL PRIMARY KEY,
    fingerprint      VARCHAR(64)  NOT NULL,
    imported_at      TIMESTAMPTZ  NOT NULL,
    downloaded_bytes BIGINT       NOT NULL
);
