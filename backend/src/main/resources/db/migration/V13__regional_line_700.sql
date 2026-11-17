-- Line 700 is regional too (RouteSubtype.classify): like 400–499, it is another operator's line to the towns of the
-- region. Only routes still on the default for their number (suburban) change; a subtype set by hand is kept.
UPDATE route SET subtype = 'regional'
WHERE subtype = 'suburban' AND coalesce(nullif(short_name, ''), long_name) ~ '^0*700$';
