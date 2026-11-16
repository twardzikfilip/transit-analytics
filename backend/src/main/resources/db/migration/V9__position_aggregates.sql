-- Continuous aggregates and background policies for position_sample. TimescaleDB doesn't allow these inside a
-- transaction, so this script runs without one (V9__position_aggregates.sql.conf).

-- Fleet size and punctuality every 5 minutes.
CREATE MATERIALIZED VIEW IF NOT EXISTS fleet_5min WITH (timescaledb.continuous) AS
SELECT time_bucket(INTERVAL '5 minutes', observed_at) AS bucket,
       count(DISTINCT vehicle_id)                     AS vehicles,
       count(*)                                       AS samples,
       avg(delay_seconds)::double precision           AS mean_delay_seconds
FROM position_sample
GROUP BY bucket
WITH NO DATA;

-- Delay per route and hour. stats_agg gives mean/stddev (for outliers), percentile_agg gives median/p90/...;
-- read them with average(delay_stats), stddev(delay_stats), approx_percentile(0.9, delay_pct).
CREATE MATERIALIZED VIEW IF NOT EXISTS route_delay_hourly WITH (timescaledb.continuous) AS
SELECT time_bucket(INTERVAL '1 hour', observed_at)      AS bucket,
       route_name,
       count(*)                                         AS samples,
       count(DISTINCT vehicle_id)                       AS vehicles,
       stats_agg(delay_seconds::double precision)       AS delay_stats,
       percentile_agg(delay_seconds::double precision)  AS delay_pct,
       min(delay_seconds)                               AS min_delay_seconds,
       max(delay_seconds)                               AS max_delay_seconds
FROM position_sample
WHERE delay_seconds IS NOT NULL
GROUP BY bucket, route_name
WITH NO DATA;

-- Real-time aggregation: queries combine the materialised part with the newest raw rows, so dashboards are current.
ALTER MATERIALIZED VIEW fleet_5min SET (timescaledb.materialized_only = false);
ALTER MATERIALIZED VIEW route_delay_hourly SET (timescaledb.materialized_only = false);

SELECT add_continuous_aggregate_policy('fleet_5min',
       start_offset => INTERVAL '2 days', end_offset => INTERVAL '5 minutes',
       schedule_interval => INTERVAL '10 minutes', if_not_exists => true);
SELECT add_continuous_aggregate_policy('route_delay_hourly',
       start_offset => INTERVAL '2 days', end_offset => INTERVAL '1 hour',
       schedule_interval => INTERVAL '30 minutes', if_not_exists => true);

-- Compress raw position chunks once they are a week old.
CALL add_columnstore_policy('position_sample', after => INTERVAL '7 days', if_not_exists => true);

GRANT SELECT ON fleet_5min, route_delay_hourly TO grafana_reader;
