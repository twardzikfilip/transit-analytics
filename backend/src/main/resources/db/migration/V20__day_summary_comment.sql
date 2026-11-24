-- V19's description was too simple: trip outcomes also come from the timetable, whose imports drop past trips.
COMMENT ON TABLE day_summary IS 'Reports'' rows of service days that are over (DaySummaries). Can be worked out again '
    'from the recorded positions, except trip-outcomes of days whose trips are gone from the timetable: do not empty.';
