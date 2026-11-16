-- Reshape the timetable tables to follow GTFS (https://gtfs.org/documentation/schedule/reference/), so a GTFS feed
-- can be loaded as-is. Every GTFS record keeps its feed id in gtfs_id; our own BIGSERIAL ids stay the primary keys,
-- so re-importing a feed updates rows in place instead of changing their ids.

-- Remove the Berlin sample data from V3; these tables are filled from GTFS from now on.
DELETE FROM trip;
DELETE FROM stop;
DELETE FROM route;

-- agency.txt
CREATE TABLE agency (
    id       BIGSERIAL PRIMARY KEY,
    gtfs_id  VARCHAR(64)  NOT NULL UNIQUE,
    name     VARCHAR(255) NOT NULL,
    url      VARCHAR(512) NOT NULL,
    timezone VARCHAR(64)  NOT NULL,
    lang     VARCHAR(16),
    phone    VARCHAR(64),
    fare_url VARCHAR(512),
    email    VARCHAR(255)
);

-- routes.txt. GTFS only requires one of short_name/long_name, and route_type is a numeric code (3 = bus).
ALTER TABLE route
    ADD COLUMN gtfs_id     VARCHAR(64) NOT NULL UNIQUE,
    ADD COLUMN agency_id   BIGINT REFERENCES agency (id) ON DELETE SET NULL,
    ADD COLUMN description TEXT,
    ADD COLUMN url         VARCHAR(512),
    ADD COLUMN text_color  VARCHAR(7),
    ADD COLUMN sort_order  INTEGER,
    ALTER COLUMN short_name DROP NOT NULL,
    DROP COLUMN type,
    ADD COLUMN route_type  SMALLINT NOT NULL;

-- stops.txt. Coordinates are optional in GTFS for some location types (e.g. generic nodes).
ALTER TABLE stop
    ADD COLUMN gtfs_id             VARCHAR(64) NOT NULL UNIQUE,
    ADD COLUMN description         TEXT,
    ADD COLUMN zone_id             VARCHAR(64),
    ADD COLUMN url                 VARCHAR(512),
    ADD COLUMN location_type       SMALLINT,
    ADD COLUMN parent_station      VARCHAR(64),
    ADD COLUMN wheelchair_boarding SMALLINT,
    ADD COLUMN platform_code       VARCHAR(32),
    ALTER COLUMN latitude DROP NOT NULL,
    ALTER COLUMN longitude DROP NOT NULL;

-- trips.txt. Start and end times are not part of a GTFS trip; they come from its stop times.
ALTER TABLE trip
    ADD COLUMN gtfs_id               VARCHAR(64) NOT NULL UNIQUE,
    ADD COLUMN service_id            VARCHAR(64) NOT NULL,
    ADD COLUMN short_name            VARCHAR(64),
    ADD COLUMN block_id              VARCHAR(64),
    ADD COLUMN shape_id              VARCHAR(64),
    ADD COLUMN wheelchair_accessible SMALLINT,
    ADD COLUMN bikes_allowed         SMALLINT,
    DROP COLUMN start_time,
    DROP COLUMN end_time;

CREATE INDEX idx_trip_service ON trip (service_id);

-- stop_times.txt. Times stay in GTFS form "HH:MM:SS"; they can go past 24:00:00 for trips that run after midnight.
CREATE TABLE stop_time (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT  NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
    stop_id             BIGINT  NOT NULL REFERENCES stop (id) ON DELETE CASCADE,
    stop_sequence       INTEGER NOT NULL,
    arrival_time        VARCHAR(8),
    departure_time      VARCHAR(8),
    stop_headsign       VARCHAR(255),
    pickup_type         SMALLINT,
    drop_off_type       SMALLINT,
    shape_dist_traveled DOUBLE PRECISION,
    timepoint           SMALLINT,
    UNIQUE (trip_id, stop_sequence)
);

CREATE INDEX idx_stop_time_stop_departure ON stop_time (stop_id, departure_time);

-- calendar_dates.txt
CREATE TABLE calendar_date (
    id             BIGSERIAL PRIMARY KEY,
    service_id     VARCHAR(64) NOT NULL,
    date           DATE        NOT NULL,
    exception_type SMALLINT    NOT NULL,
    UNIQUE (service_id, date)
);

-- shapes.txt
CREATE TABLE shape_point (
    id                  BIGSERIAL PRIMARY KEY,
    shape_id            VARCHAR(64)      NOT NULL,
    sequence            INTEGER          NOT NULL,
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    shape_dist_traveled DOUBLE PRECISION,
    UNIQUE (shape_id, sequence)
);
