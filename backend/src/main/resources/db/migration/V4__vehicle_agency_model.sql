-- Properties provided by external live feeds (e.g. zbiorkom.live): operator and vehicle model.
ALTER TABLE vehicle
    ADD COLUMN agency VARCHAR(64),
    ADD COLUMN model  VARCHAR(128);
