-- Vehicle position history (time series) from the zbiorkom.live feed, stored with TimescaleDB.
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS timescaledb_toolkit;

-- One row per vehicle position report. A vehicle reports about once a minute, so this grows by roughly
-- (vehicles in service) rows per minute: ~25 million rows in half a year.
CREATE TABLE position_sample (
    observed_at        TIMESTAMPTZ      NOT NULL, -- the feed's timestamp for this position
    fetched_at         TIMESTAMPTZ,              -- when we polled the feed (for imported files: latest position time)
    vehicle_id         BIGINT           NOT NULL REFERENCES vehicle (id),
    route_name         TEXT,                     -- e.g. "103"; equals route.gtfs_id for BKM lines
    brigade            TEXT,                     -- duty, e.g. "103-01"; GTFS trip ids start with it
    headsign           TEXT,
    latitude           DOUBLE PRECISION NOT NULL,
    longitude          DOUBLE PRECISION NOT NULL,
    bearing            SMALLINT,
    delay_seconds      INTEGER,                  -- against the timetable; negative when early
    next_stop_name     TEXT,
    next_stop_sequence SMALLINT
);

-- Partitioned by time into weekly chunks.
SELECT create_hypertable('position_sample', by_range('observed_at', INTERVAL '7 days'));

-- The same report seen in two polls (or two imported files) is stored once.
CREATE UNIQUE INDEX position_sample_vehicle_time ON position_sample (vehicle_id, observed_at);
CREATE INDEX position_sample_route_time ON position_sample (route_name, observed_at DESC);

-- Columnar compression: rows of one vehicle are stored together, ordered by time. Chunks are compressed by a
-- policy (V9) once they are a week old; compressed chunks stay fully queryable.
ALTER TABLE position_sample SET (
    timescaledb.enable_columnstore = true,
    timescaledb.segmentby = 'vehicle_id',
    timescaledb.orderby = 'observed_at DESC'
);

-- Read-only login for Grafana: position history, vehicles and the timetable, but not users or passwords.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grafana_reader') THEN
        CREATE ROLE grafana_reader LOGIN;
    END IF;
END
$$;
ALTER ROLE grafana_reader PASSWORD '${grafana_db_password}';
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO grafana_reader', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO grafana_reader;
GRANT SELECT ON position_sample, vehicle, agency, route, stop, trip, stop_time, calendar_date, shape_point
    TO grafana_reader;
