-- Days a feed's timetable doesn't reach, continued with the same weekday's services of its last known week. Their rows
-- in calendar_date are dropped with every import and made again; these marks stay, as days already judged by them do.
CREATE TABLE assumed_timetable_day (
    feed VARCHAR(32) NOT NULL,
    date DATE        NOT NULL,
    PRIMARY KEY (feed, date)
);
