-- Which GTFS feed each timetable row came from, in its own column instead of a prefix on its ids ("pksnova:500").
-- Feeds reuse each other's ids (stop "536", service "319", agency "1"), so ids are unique per feed. 'bialystok' is
-- the main feed (komunikacja.bialystok.pl); the others are the keys under app.gtfs.extra.

ALTER TABLE agency        ADD COLUMN feed VARCHAR(32) NOT NULL DEFAULT 'bialystok';
ALTER TABLE route         ADD COLUMN feed VARCHAR(32) NOT NULL DEFAULT 'bialystok';
ALTER TABLE stop          ADD COLUMN feed VARCHAR(32) NOT NULL DEFAULT 'bialystok';
ALTER TABLE trip          ADD COLUMN feed VARCHAR(32) NOT NULL DEFAULT 'bialystok';
ALTER TABLE calendar_date ADD COLUMN feed VARCHAR(32) NOT NULL DEFAULT 'bialystok';
ALTER TABLE shape_point   ADD COLUMN feed VARCHAR(32) NOT NULL DEFAULT 'bialystok';

-- Rows loaded with a prefix: the prefix becomes the feed ("pksnova:" was the feed pksnova-500) and leaves the ids.
CREATE FUNCTION pg_temp.feed_of(id TEXT) RETURNS TEXT LANGUAGE SQL IMMUTABLE AS $$
    SELECT CASE split_part(id, ':', 1) WHEN 'pksnova' THEN 'pksnova-500' ELSE split_part(id, ':', 1) END
$$;
CREATE FUNCTION pg_temp.unprefixed(id TEXT) RETURNS TEXT LANGUAGE SQL IMMUTABLE AS $$
    SELECT CASE WHEN strpos(id, ':') > 0 THEN substr(id, strpos(id, ':') + 1) ELSE id END
$$;

UPDATE agency SET feed = pg_temp.feed_of(gtfs_id), gtfs_id = pg_temp.unprefixed(gtfs_id)
WHERE strpos(gtfs_id, ':') > 0;
UPDATE route SET feed = pg_temp.feed_of(gtfs_id), gtfs_id = pg_temp.unprefixed(gtfs_id)
WHERE strpos(gtfs_id, ':') > 0;
UPDATE stop SET feed = pg_temp.feed_of(gtfs_id), gtfs_id = pg_temp.unprefixed(gtfs_id),
                parent_station = pg_temp.unprefixed(parent_station)
WHERE strpos(gtfs_id, ':') > 0;
UPDATE trip SET feed = pg_temp.feed_of(gtfs_id), gtfs_id = pg_temp.unprefixed(gtfs_id),
                service_id = pg_temp.unprefixed(service_id), block_id = pg_temp.unprefixed(block_id),
                shape_id = pg_temp.unprefixed(shape_id)
WHERE strpos(gtfs_id, ':') > 0;
UPDATE calendar_date SET feed = pg_temp.feed_of(service_id), service_id = pg_temp.unprefixed(service_id)
WHERE strpos(service_id, ':') > 0;
UPDATE shape_point SET feed = pg_temp.feed_of(shape_id), shape_id = pg_temp.unprefixed(shape_id)
WHERE strpos(shape_id, ':') > 0;

ALTER TABLE agency DROP CONSTRAINT agency_gtfs_id_key, ADD UNIQUE (feed, gtfs_id);
ALTER TABLE route DROP CONSTRAINT route_gtfs_id_key, ADD UNIQUE (feed, gtfs_id);
ALTER TABLE stop DROP CONSTRAINT stop_gtfs_id_key, ADD UNIQUE (feed, gtfs_id);
ALTER TABLE trip DROP CONSTRAINT trip_gtfs_id_key, ADD UNIQUE (feed, gtfs_id);
ALTER TABLE calendar_date DROP CONSTRAINT calendar_date_service_id_date_key, ADD UNIQUE (feed, service_id, date);
ALTER TABLE shape_point DROP CONSTRAINT shape_point_shape_id_sequence_key, ADD UNIQUE (feed, shape_id, sequence);

-- Lines are looked up by their id alone (/routes/route/500).
CREATE INDEX idx_route_gtfs_id ON route (gtfs_id);
DROP INDEX idx_trip_service;
CREATE INDEX idx_trip_service ON trip (feed, service_id);
