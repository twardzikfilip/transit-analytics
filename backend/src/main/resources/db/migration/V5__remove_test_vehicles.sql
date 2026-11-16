-- Remove test vehicles now that the vehicle table is filled from the live Białystok feed.
-- Their stored positions are deleted with them (ON DELETE CASCADE); trips keep their route but lose the vehicle
-- (ON DELETE SET NULL).

-- The two Berlin sample buses seeded in V3.
DELETE FROM vehicle WHERE vehicle_number IN ('BUS-101', 'BUS-102');

-- A test bus created by hand in a local database. Matched on all its test values so a real feed vehicle 930
-- (which has a model) is never removed; on other databases this deletes nothing.
DELETE FROM vehicle WHERE vehicle_number = '930' AND capacity = 30 AND model IS NULL;
