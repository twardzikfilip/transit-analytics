-- Lines 400–499 are regional (RouteSubtype.classify). Lines already set by hand to something other than the default
-- (suburban, for numbers from 100 up) keep their subtype.
UPDATE route SET subtype = 'regional'
WHERE subtype = 'suburban' AND coalesce(nullif(short_name, ''), long_name) ~ '^0*4[0-9]{2}$';
