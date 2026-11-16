-- Kind of line: urban (city), suburban (numbers 100 and up, reaching the towns around Białystok), night_urban (N1, N2, …)
-- and regional. The GTFS import sets it for new routes with the same rule (RouteSubtype.classify) and keeps it for
-- existing ones, so a manual change survives re-imports.
ALTER TABLE route
    ADD COLUMN subtype VARCHAR(16) NOT NULL DEFAULT 'urban'
        CHECK (subtype IN ('urban', 'suburban', 'night_urban', 'regional'));

UPDATE route SET subtype = CASE
    WHEN coalesce(nullif(short_name, ''), long_name) ~* '^N[0-9]+$' THEN 'night_urban'
    -- A number of 100 or more (written without a cast, so other names never fail to convert).
    WHEN coalesce(nullif(short_name, ''), long_name) ~ '^0*[1-9][0-9]{2,}$' THEN 'suburban'
    ELSE 'urban'
END;
