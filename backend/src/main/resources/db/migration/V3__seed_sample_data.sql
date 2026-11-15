-- Small sample data set so the UI has something to show.
INSERT INTO route (short_name, long_name, type, color) VALUES ('1', 'Central Station - Airport', 'BUS', '#1e88e5');

INSERT INTO stop (code, name, latitude, longitude) VALUES
    ('CS', 'Central Station', 52.5251, 13.3694),
    ('AP', 'Airport', 52.3667, 13.5033);

INSERT INTO vehicle (vehicle_number, type, capacity, active) VALUES
    ('BUS-101', 'BUS', 80, TRUE),
    ('BUS-102', 'BUS', 80, TRUE);

INSERT INTO trip (route_id, vehicle_id, headsign, direction, start_time)
SELECT r.id, v.id, 'Airport', 0, now() FROM route r, vehicle v
WHERE r.short_name = '1' AND v.vehicle_number = 'BUS-101';

INSERT INTO vehicle_position (vehicle_id, latitude, longitude, bearing, speed)
SELECT id, 52.5200, 13.4050, 90, 12.5 FROM vehicle WHERE vehicle_number = 'BUS-101';
INSERT INTO vehicle_position (vehicle_id, latitude, longitude, bearing, speed)
SELECT id, 52.5000, 13.4500, 180, 8.0 FROM vehicle WHERE vehicle_number = 'BUS-102';
