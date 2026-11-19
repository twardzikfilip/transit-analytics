-- Login for transit-recorder (backend/recorder), which polls the live feed and stores positions: it may add vehicles
-- seen for the first time and position reports, and read them back, nothing else. The schema stays the backend's.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'transit_recorder') THEN
        CREATE ROLE transit_recorder LOGIN;
    END IF;
END
$$;
ALTER ROLE transit_recorder PASSWORD '${recorder_db_password}';
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO transit_recorder', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO transit_recorder;
GRANT SELECT, INSERT ON vehicle, position_sample TO transit_recorder;
GRANT USAGE ON SEQUENCE vehicle_id_seq TO transit_recorder;
