-- Size class of each bus (from its model), and two classifications of the lines: their geometry relative to the
-- city centre and their capacity. Filled by the application from classification/*.yml; null until classified.
ALTER TABLE vehicle ADD COLUMN vehicle_class VARCHAR(16);
ALTER TABLE route ADD COLUMN geometry VARCHAR(16);
ALTER TABLE route ADD COLUMN capacity_class VARCHAR(16);
