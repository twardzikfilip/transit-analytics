-- The train timetables (feed "rail") as they were published, day by day. An import replaces the feed's rows in trip,
-- stop_time and calendar_date, so past days would be gone; here they stay. A train running the same on many days is
-- stored once.
CREATE TABLE rail_train (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key      VARCHAR(32) NOT NULL UNIQUE, -- md5 of everything below and its calls
    agency   VARCHAR(64) NOT NULL,        -- PR (Polregio) or IC (PKP Intercity)
    gtfs_id  VARCHAR(64) NOT NULL,        -- of the trip
    number   TEXT,                        -- the train's number and name
    headsign TEXT
);

CREATE TABLE rail_train_call (
    train_id       BIGINT      NOT NULL REFERENCES rail_train (id) ON DELETE CASCADE,
    stop_sequence  INTEGER     NOT NULL,
    station_id     VARCHAR(64) NOT NULL,
    station        TEXT        NOT NULL,
    stop_id        VARCHAR(64) NOT NULL,  -- with platform and track where known
    arrival_time   VARCHAR(16),
    departure_time VARCHAR(16),
    PRIMARY KEY (train_id, stop_sequence)
);

-- The days each train runs: for past days as last published before or on the day.
CREATE TABLE rail_train_day (
    date     DATE   NOT NULL,
    train_id BIGINT NOT NULL REFERENCES rail_train (id) ON DELETE CASCADE,
    PRIMARY KEY (date, train_id)
);
