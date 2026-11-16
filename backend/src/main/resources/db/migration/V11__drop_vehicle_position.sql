-- The vehicle_position table and trip.vehicle_id came with the original CRUD template and were never used: positions
-- are recorded in position_sample (V8), and GTFS trips aren't tied to one vehicle.
DROP TABLE IF EXISTS vehicle_position;
ALTER TABLE trip DROP COLUMN IF EXISTS vehicle_id;
