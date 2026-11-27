-- City Rail proposal no. 1, worked out once: per solution and frequency, its lines and timetables. An analysis of the
-- trains' timetable as it was on one weekday; worked out again only when the network or the way it is worked out
-- changes (network_hash).
CREATE TABLE city_rail_proposal_variant (
    solution     VARCHAR(16) NOT NULL,
    frequency    VARCHAR(8)  NOT NULL,
    network_hash VARCHAR(64) NOT NULL,
    day          DATE        NOT NULL,
    computed_at  TIMESTAMPTZ NOT NULL,
    -- The rules and which railways trains run on (the same in every row), and the variant's lines.
    meta         JSONB       NOT NULL,
    variant      JSONB       NOT NULL,
    PRIMARY KEY (solution, frequency)
);
