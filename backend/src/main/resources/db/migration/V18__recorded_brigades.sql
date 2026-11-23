-- Brigades of the lines whose timetable doesn't name them (Wschód Express, Turośń Kościelna), learnt from the recorded
-- positions (RecordedBrigades). Their buses report brigade numbers such as "1" that repeat from line to line: "1" on
-- lines 414, 415 and 416 is one bus, "1" on line 400 another. A brigade is a number on a group of lines one bus runs,
-- named after the group's first line: 414-1, 400-1, 700-T1.

-- The brigade each live brigade number on a line belongs to.
CREATE TABLE recorded_brigade_line (
    agency       VARCHAR(64) NOT NULL, -- as the live feed names the operator (vehicle.agency)
    line         VARCHAR(64) NOT NULL, -- route_name of the reports
    live_brigade VARCHAR(64) NOT NULL, -- brigade of the reports
    brigade      VARCHAR(64) NOT NULL,
    PRIMARY KEY (agency, line, live_brigade)
);

-- The brigade a timetable trip belongs to. These feeds give each date its own trips, so it is learnt for the run (the
-- line's trip between two stops at a time of day, on a type of day): the brigade whose bus ran it on most days.
CREATE TABLE trip_brigade (
    trip_id BIGINT PRIMARY KEY REFERENCES trip (id) ON DELETE CASCADE,
    brigade VARCHAR(64) NOT NULL,
    days    INTEGER     NOT NULL -- days the run was seen run by that brigade
);
