-- Reports' rows of service days that are over (DaySummaries), so a restart needn't work them out from the recorded
-- positions again. A cache: deleting rows loses nothing while the positions are there.
CREATE TABLE day_summary (
    report      VARCHAR(64) NOT NULL,
    day         DATE        NOT NULL, -- service day
    fingerprint VARCHAR(64) NOT NULL, -- of the report's logic and inputs; another one means out of date
    computed_at TIMESTAMPTZ NOT NULL,
    positions   BIGINT      NOT NULL, -- the day's recorded positions then (DaySummaries.positions)
    content     BYTEA       NOT NULL, -- the rows as gzipped JSON
    PRIMARY KEY (report, day)
);
