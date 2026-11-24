# Transit Analytics

Starter full-stack app: Angular 22 + NgRx frontend, Spring Boot 4 backend, PostgreSQL with Flyway migrations.

```
docker-compose.yml   PostgreSQL 17 + TimescaleDB, Grafana LGTM (telemetry) for local development
backend/             Spring Boot 4.1 (Gradle, Java 17): JWT auth + CRUD REST API
frontend/            Angular 22 (standalone, signals, NgRx, Leaflet)
```

## Prerequisites

- Java 17+
- Node 24 (pinned via [Volta](https://volta.sh) in `frontend/package.json`; Angular 22 needs Node ≥ 22.22)
- Docker or Podman for the database (or any PostgreSQL 13+ you point the backend at)

## Run it

```bash
# 1. Database + telemetry stack (Grafana on http://localhost:3000)
docker compose up -d postgres otel-lgtm

# 2. Backend  → http://localhost:8080 (Flyway migrates on startup)
cd backend && ./gradlew bootRun

# 3. Recorder (polls the live feed into the database, see "The recorder"); after the backend's first start
backend/recorder/upgrade.sh

# 4. Frontend → http://localhost:4200 (proxies /api to :8080)
cd frontend && npm install && npm start
```

Seeded dev users (from `V2__seed_users.sql` — **change before deploying anywhere**):

| user  | password | role  |
|-------|----------|-------|
| admin | admin    | ADMIN |
| guest | guest    | GUEST |

## Backend

| Setting | Env var | Default |
|---|---|---|
| JDBC URL | `DB_URL` | `jdbc:postgresql://localhost:5432/transit` |
| DB user / password | `DB_USER` / `DB_PASSWORD` | `transit` / `transit` |
| JWT signing secret (≥ 32 bytes) | `JWT_SECRET` | dev-only value |
| JWT lifetime | `JWT_EXPIRATION_MINUTES` | `480` |
| CORS origins | `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` |
| zbiorkom.live API base URL | `ZBIORKOM_BASE_URL` | `https://api.zbiorkom.live/api6-open` |
| Białystok bounding box (`minLon,minLat,maxLon,maxLat`) | `ZBIORKOM_BIALYSTOK_BBOX` | `22.45,52.88,23.48,53.44` |
| Białystok GTFS feed (zip) | `GTFS_BIALYSTOK_URL` | `https://komunikacja.bialystok.pl/cms/File/download/gtfs/google_transit.zip` |
| transit-recorder address (Live map) | `RECORDER_URL` | `http://localhost:8081` |
| Password of the recorder's database role | `RECORDER_DB_PASSWORD` | `transit_recorder` |
| Raw snapshot archive folder | `RECORDING_ARCHIVE_DIR` | `data/raw` (inside `backend/`, git-ignored) |
| Password of the read-only `grafana_reader` DB user | `GRAFANA_DB_PASSWORD` | `grafana_reader` (set the same value for docker compose) |

### API

- `GET /api/version` → `{backend, recorder}`, each `{version, released}`: the versions running (`recorder` null when it doesn't answer)
- `POST /api/auth/login` `{username, password}` → `{token, username, role}`; `GET /api/auth/me`
- CRUD (`GET` list paged with `?page=&size=&sort=`, `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`) on
  `/api/vehicles`, `/api/users`, `/api/roles`, and the timetable (GTFS) tables
  `/api/agencies`, `/api/routes`, `/api/stops`, `/api/trips`, `/api/stop-times`, `/api/calendar-dates`,
  `/api/shape-points`. Timetable lists take filters as query parameters, e.g. `/api/trips?routeId=17&direction=1`,
  `/api/stop-times?stopId=260&sort=departureTime&sort=id`, `/api/stops?q=dworzec` (Swagger lists them all).
- Position history (recorded time series), each with a window `from`/`to` (ISO-8601, `to` exclusive, at most a year;
  see *Time windows* below), an optional `route` (line) and an optional `agency` (operator, e.g. `BKM`; all operators
  when left out — the Home page asks for BKM unless another operator is chosen):
  - `GET /api/positions/fleet` — per bucket of polls: vehicles running (average per poll), the most at once,
    reports and mean delay. Buckets are 1 minute (one poll) for windows up to 24 h and coarser beyond, keeping
    ≤ 1,500 points. The totals separate *running now* (latest poll) and *most at once* (peak poll) from *different
    vehicles seen*. The last is always larger, because vehicles start and end their runs during the window.
  - `GET /api/positions/delays` — vehicles and reports per delay band: on time (under 2 min late, or early), 2–5,
    5–10, and 10 min or more. A vehicle counts in every band it was in.
  - `GET /api/positions/routes` — lines recorded in the window.
  - `GET /api/positions/agencies` — the operators on the feed, BKM first, for the operator filter.
- `GET /api/lines/stats` — per line, from the regular weekday timetable: length (main route, km), shortest and
  longest end-to-end travel time, average speed, interval, regularity (coefficient of variation of the gaps between
  departures, graded A–F as in TCQSM) and vehicles in peak (06:30–08:30, 15:00–17:00) and off-peak
  (08:30–15:00, 17:00–20:00) hours, the same periods as on every timetable page; and, recorded, each line's mean
  delay in peak and off-peak hours on weekdays of the last `delayDays` (28) days (`peakDelaySeconds`,
  `offPeakDelaySeconds`; from the hourly `route_delay_hourly` aggregate, an hour counting in the part of the day its
  middle falls in). Routes ranks the lines by these and shows each line's regularity; the definitions are on the
  Routes page.
- **Time windows.** Every report over recorded positions (position history, Fleet, hotspots, delay generation,
  abnormal trips) takes `from` and `to` (ISO-8601 instants, `to` exclusive). Both are optional: `to` defaults to now,
  and without `from` the window starts at the first recorded position (*all time*). `from` after `to` is a 400.
  Answers for the same window are kept for a minute or two (two requests for one window at once compute it once).
  Service days that are over are computed once and kept for 92 days; a window that cuts a day takes from the whole
  day what starts inside it, so its edges round to what the report counts in: a standstill or run where it starts,
  a bus-lane half hour, a Fleet hour. The running day is read in pieces: what is finished is kept, and each refresh
  reads only from the oldest standstill, run or bucket still going on, giving what reading the whole day gives (the
  whole day is read again if positions are stored late in the part kept, e.g. after a database outage). Abnormal
  trips, Trips and brigades keep the running day's reports in memory the same way.
  Days that are over are also stored in the database (table `day_summary`, about 2 MB a day: standstills, delay runs,
  bus-lane half hours, Fleet hours and trip outcomes), so a restart reads them instead of the positions. They are
  worked out one at a time. A stored day is worked out again when the report's logic or the bus
  lanes change, or when positions of it turn up later (checked every night at 06:30). Trip outcomes also need the
  timetable, whose imports drop past trips: a stored day's outcomes stay when some of its trips are gone, and neither
  an import nor newly learnt brigades change them. The other reports can always be worked out again from the
  positions; **don't empty the table by hand**, old days' trip outcomes would be lost. Nothing is stored with less than
  5 GB free on the backend's disk, with a table over 1 GB, or with `DAY_SUMMARIES_ENABLED=false`.
- `DELETE /api/admin/day-summaries?from=&to=&report=` (admin): drops stored days (service days, both included) of one
  report (`hotspots`, `delay-generation`, `bus-lanes`, `fleet`, `trip-outcomes`) or of all, to work them out again (trip outcomes are kept until they have been).
- Routes (`{line}` is the route's GTFS id, e.g. `103`):
  - `GET /api/lines` — all lines in numeric order, with their subtype, `geometry` and `capacityClass` (see
    *Vehicle and line classes*): the timetable's, and the lines only seen on the live feed (other operators' regional
    lines, 400–499 and 700), which have no `id` and no classes.
  - `GET /api/lines/{line}/detail` — the route's `id` (for its trips), its `geometry` and `capacityClass`, and `url` (its timetable on the operator's
    website); from the weekday timetable: every variant (direction and shape, with its route
    shape, stops, length, trips and scheduled travel times; the most frequent one per direction is the base route,
    the others are longer, shorter or depot runs) with its scheduled travel times and average speeds, the base
    route's length, travel time and speed, the part in fare zone 2 on the base route and longer variants (stops
    and km; the stretch crossing the border counts half), every brigade (first departure, last arrival, runs and
    breaks of 30 min or more), the standard end stops, and the depot runs with their stops. Standard end stops are
    where a brigade starts its next run within 30 min; end stops less than 500 m from each other (e.g. arrival and
    departure platforms) are one, named after the stop where runs end. Depot-run stops are grouped the same way. A depot run starts or ends elsewhere, as a brigade's first or
    last run or next to a break of 30 min or more. A long wait at a standard end stop is a break.
  - `GET /api/lines/{line}/performance` — from recorded positions on weekdays: travel time and average speed of full
    runs by departure hour (min, max, mean, median), per route pattern (base route, each longer variant; both
    directions), average delay per 15 minutes, and speed between consecutive stops for runs leaving in peak hours.
    Also, from all recorded days, the buses that served the line: days per bus, the most used bus per brigade, and
    brigades that changed bus during a day. A stop counts as passed midway between the two
    reports that bracket it (one a minute), so single runs are accurate to about ±30 s.
- `GET /api/corridors` — important corridors from the weekday timetable, built in three steps: (1) for each set of
  2+ lines, the longest stretch of at least 4 consecutive stops they all run along; (2) a shorter one inside a longer
  one is dropped unless it carries at least 1.5× the buses; (3) the rest are joined end to end where the way on
  doesn't fork (all continuations lead to the same next stop). No corridor passes the same stop (name) twice. Both
  directions are one row when at least half the lines run back between the same ends or along half the same stops.
  For each: stops, all lines and those running all of it, length, and buses, interval and regularity (CV of the
  gaps, graded A–F) in peak (06:30–08:30, 15:00–17:00) and off-peak (08:30–15:00, 17:00–20:00) hours, measured per
  stretch between stops and averaged along the corridor by length. Busiest first.
  Also every stretch between two consecutive stops (both directions together) with its peak and all-day buses and
  its route shape, for the maps. Computed in the background at startup and cached per timetable.
- `POST /api/gtfs/{feed}/import` (admin) — downloads a GTFS timetable and loads it: `bialystok` (the city's) or
  another operator's feed from `app.gtfs.extra` (`pksnova-500`, `wschodexpress`, `turosn-koscielna`); see below.
- `GET /api/zbiorkom/bialystok/positions` — live Białystok vehicle positions from [zbiorkom.live](https://zbiorkom.live),
  used by the live map: transit-recorder's latest snapshot (at most 10 s old), or straight from zbiorkom.live when the
  recorder doesn't answer. Vehicles seen for the first time are added to the vehicle table (number, type, agency,
  model; the backend adds the class and capacity of its model within 5 minutes). The agency `default` is stored as `BKM`, and GTFS type codes become names
  (`3` → `BUS`). Each position carries its line's `subtype` (the stored one, else from the name, for other operators'
  lines). If zbiorkom.live fails or times out, the endpoint returns 502.
- `GET /api/fleet?from=&to=[&agency=BKM&agency=…]` — Fleet, from recorded positions: buses in use and in the fleet
  (every bus ever seen on the feed) and per model, per size class (`classes`) and per make (`makes`), the 10 most and least used (hours in service and lines), every
  bus's hours (unused ones with 0), and the buses that broke down (replaced midway through a trip and not used again
  that day, as on Abnormal trips) with when they served a line again and how many days that took. `agency`
  (repeatable) limits everything to those operators' buses; `agencies` lists every operator of the fleet. The last
  month is computed at startup in the background.
- `GET /api/fleet/vehicles/{id}?from=&to=` — one bus: its runs (consecutive reports on one line, brigade and headsign)
  and breaks of 30 min or more per service day, hours per day, and the abnormal trips it was involved in (trips it
  broke down on, each with its `story` and `timeline` as on Abnormal trips and the `paths` of both buses over that
  time from their recorded positions, and its brigade's trips it missed).
- `GET /api/hotspots?from=&to=[&line=21]` — Stop hotspots: where buses of the timetable's lines stand
  still away from stops. A standstill is two or more reports in a row within 10 m (up to 5 min; longer ones are
  layovers or breakdowns); those within 50 m of a stop or 150 m of a terminal are left out. The rest are clustered
  with DBSCAN (25 m, at least 5 standstills, 3 different buses) into hotspots, the most time lost first, each with
  its standstills, buses, typical and long wait, the directions buses were heading, the busiest hour and the lines.
  Per-day figures are over the days with recordings. Service days that are over are read once and kept; the last
  month is read at startup in the background. Cached for 2 minutes.
- `GET /api/bus-lanes` — the city's bus lanes from OpenStreetMap (`places/osm/bus-lanes.json`, or the refreshed copy in
  `./config/map/bus-lanes.json`): each street section with how many lanes run each way and whether buses have a lane
  the other way too (on the section or the other carriageway), the sections grouped by street and direction, the
  length of bus lane (24.8 km) and the city's own figure (24 km, January 2025) for comparison.
  `POST /api/bus-lanes/refresh` (admin) fetches them again from Overpass (also monthly, `refresh-cron` in
  `map/bus-lanes.yml`; servers tried in turn; on failure the lanes stay as they are).
- `GET /api/bus-lanes/report?from=&to=` — speeds and delays on bus lanes and elsewhere: each pair of consecutive
  reports of a bus (5–90 s apart, timetable lines, inside the city, layovers at termini left out) counts as on a bus
  lane when its middle is within 15 m of one and the bus heads its way (45°); within 30 m of a lane running the other
  way (and no lane its own way) it is on the same street in the direction without a lane. Speed (stops included),
  weekday peak and off-peak speed, peak slowdown, and delay gained per km (within a trip) on lanes, the same streets
  the other way, and elsewhere; by weekday hour; and per stretch (street and direction) with the other way's figures.
  Service days that are over are read once and kept; the last week is read at startup. Cached for 2 minutes.
  Line details (`/api/lines/{line}/detail`) give `busLanes`: how much of each direction's main route runs on bus
  lanes, with those parts.
- `GET /api/delay-generation?from=&to=` — Delay generation: how much delay buses gain (or recover)
  between consecutive stops, on the timetable's lines. A passage is a report whose next stop moved on by one; the
  feed's delay then is the delay on leaving the stop just passed, and the change between two consecutive stops is the
  delay generated there (the drive and the dwell at the second stop). Reports that skip a stop, and delays over
  30 minutes, are left out. Every directed stretch measured at least 5 times (all lines together) with its buses
  and average change for the whole day and per part of it (before 06:30, morning peak, midday, afternoon peak,
  evening, after 20:00), and each line's gain per run (first stop to last, runs of 5 stops or more) with its worst
  stretch. Feed stop names are matched to the timetable's by name (without the stop code and "NŻ") and the nearest
  position. Service days that are over are read once and kept; the last month is read at startup in the
  background. Cached for 2 minutes.
- `GET /api/delay-generation/lines/{line}?from=&to=` — one line: per headsign, its stretches in route order with
  the average, median and 90th percentile change, the share of buses gaining a minute or more, the delay on leaving,
  and the average by hour of the day; its brigades by delay gained per run; and example runs (the most delay gained
  overall, and on each stretch) with the time and delay on leaving every stop.
- `GET /api/trip-anomalies?from=&to=` — Abnormal trips: every trip of the timetable
  scheduled to run in the window (for any part of it), judged against the recorded positions. A report belongs to
  the trip of its brigade and line scheduled to leave the report's next stop closest to the report's time minus its
  delay (within 20 min; the headsign only breaks ties, as the feed's don't always read as the timetable's); blank
  brigades are taken from the same bus's neighbouring reports. A report whose delay is over half an hour (no use for
  placing it) stays on the trip of the same bus's report up to 5 min before, when line, brigade and headsign are the
  same: a bus broken down mid-trip, its delay growing, would otherwise look like it started the brigade's next trip. Outcomes: run in full (first seen heading to one of
  the first 3 stops, last to one of the last 2), detour (3 reports in a row over 150 m from the route shape),
  bus changed (run in full by two buses of the brigade, each reporting 3+ times on it, one after the other: the
  first past the first 3 stops and at least 200 m along, the second further than the first got; it took over at the
  same place within 300 m or heading to the same or next stop; a change on a partial trip is in its note;
  each change says where the replacement came from, and whether the replaced bus was used again that day: moving
  500 m away and reporting a brigade for 10 minutes, else it's possibly broken down — then how long it stood where
  it stopped and whether it went silent, moved off (how far) or still stands there. On a detour, `detourPath` (the bus's reports off the route, with three either side) and `detourRoute` (the
  usual route within 600 m of it), for a map. Each change is also told step by
  step (`story`: what each bus did when, in time order) with a time line per bus (`timeline`: running, standing,
  moving off, on its way without the brigade, on the brigade)),
  short-turned (the bus, reporting all along, joined midway, or left the trip more than 3 stops before the end and
  didn't drive on to it; within the last 3 stops only when it turned onto its brigade's next trip, 3 min or more less
  late there, and wasn't seen near the last stop afterwards: the feed often moves a bus on to its next trip two or
  three stops early, and buses go off to the depot from there), no signal (it stopped
  reporting partway but was back for its brigade's next trip), lost (it stopped reporting midway and didn't come
  back within 15 min), missed (not run while the brigade ran other trips; with where the brigade's bus was instead)
  and cancelled (no bus on the brigade all day). Trips during gaps in recording (3+ minutes without any report)
  aren't judged; trips are judged as soon as it's clear what the bus did. Counts overall and per line, and the
  abnormal trips (the latest 2000) with brigade, buses, stops seen, delays and a note. The `note` and each step's
  `text` are lists of phrases, `{key, params}`: the page writes them in the reader's language from `tripNotes.<key>`
  in its translations (`shared/i18n/phrases.ts`); a value is text, a number or another phrase. Each finished
  service day is judged once; the running day again after a minute. The last month is judged at startup in the background.
- `GET /api/trip-anomalies/faulty-trackers` — buses whose tracker probably fails: over the last 7 days, no-signal
  trips on 3 days or more, 5 trips or more, and at least 10% of the trips the bus ran (fewer is chance). Each with its
  no-signal and all trips, days, lines and the latest one. Worked out again every 10 minutes. Lines that ran out of
  the recording area before it was enlarged show up here until those days are a week old.
- `GET /api/trip-runs?from=&to=&q=&page=` — Trips: every trip of the timetable scheduled to run (for any part) in the
  window, in departure order, 50 a page; of a window longer than 24 h, its last 24 h (`shownFrom`). Each with its
  outcome as on Abnormal trips, what ran as on the brigade's day (each bus's part, from which stop to which, when),
  the bus's delay and speed about every minute while it ran (speed averaged over the minute; over 100 km/h is a GPS
  jump), and where it stood still on the way for 3 min or more (within 40 m; not waiting at the first stop or
  arrived at the last), with how much later it was afterwards; its stops with when each was due and when a bus
  passed it (its first report heading beyond the stop), and its average speed (the way its buses drove over the time
  it took, stops and waits included; gaps over 3 min and GPS jumps left out). `q` finds a bus by its number (the trips it ran), a
  brigade or a line (exactly), or part of a destination. The outcomes are Abnormal trips' when it has the day
  current; else the page's brigades are judged on their own, from the day's reports of their buses.
- `GET /api/trip-runs/{date}/{brigade}/{tripId}` — one trip in detail, asked for when it is opened: every report of
  its buses while they ran it (about every 10 s) with its delay and speed — 0 when the bus stood still (moved under
  5 m, or 0.4 m/s over a longer interval: GPS jitter), else the average over 15 s either side; none for a bus's first
  report, after a 2 min gap or across a GPS jump — the trip's route shape, and its stops with where they are and how
  long a bus halted within 50 m of each (the stop's pole is often across a junction from where buses halt): its steps
  there under 7 km/h, as reports come every 10 s, longer than a short halt (to about 10 s); 0 when every step there was
  12 km/h or more; null when that can't be told. 404 when the brigade
  has no such trip that day.
- `GET /api/routes/subtypes` — every line of the timetable by name with its kind (`urban`, `suburban`,
  `night_urban`, `regional`), for colouring line labels.
- `GET /api/brigades?date=&agency=` — the brigades of an operator (default: the city's, BKM) on a service day (default:
  today's): each with all its lines (a brigade with any trip of the agency is listed), trip count and first and last
  times, plus every operator: the city's agency (`BKM`), and each other operator's feed as one (`wschodexpress`).
  Other operators' brigades are learnt from the recordings (see below).
- `GET /api/brigades/{brigade}/day?date=` — a brigade's day: each trip as scheduled with what actually ran (a part per
  bus, from the first to the last stop it was seen heading to, timed by the reported delays), the parts not run (lost,
  missed, cancelled, turned short, skipped at a bus change) or not seen (the bus stopped reporting), its outcome as on
  Abnormal trips, and a summary (trips run in full, left on time between 1 min early and 3 min late, median start
  delay, buses).
- `GET /api/brigades/{brigade}/schedule` — a brigade's runs on the service day (03:00–03:00 Warsaw time) that `at`
  falls in (ISO instant, default now) from the timetable: line, headsign, first and last stop, scheduled departure
  and arrival. Brigades are named as on the live feed (`103-05`); the timetable's is the trip id's prefix. Used by
  the popups of the live map.
- `GET /api/boundaries` — Białystok's city limits and city centre (Osiedle Centrum) as closed rings of
  `[lat, lon]`, read by `domain/CityBoundaries` from `places/osm/bialystok.json` (OpenStreetMap relations 2910925 and
  4091287; © OpenStreetMap contributors, ODbL). Drawn on the live map and the other maps.
- `GET /api/network-coverage` — how much of the city lies within each walking distance of the nearest stop of the
  city's lines (400, 500 and 600 m, `map/network-coverage.yml`), of all of it and of the part outside its woods and
  the uninhabited valley of the river Biała; the woods and the valley; and each district, and each village and town
  reaching within 5 km of the city limits, with its area, residents, density (also outside its woods and the valley)
  and share near a stop. Areas are measured on a 10 m grid (20 m over the
  belt). Worked out again when the timetable's stops change.

### Reference data about places

Borders, woods, bus lanes and residents are data, not code: `backend/src/main/resources/places/`, described file by
file in its [README](backend/src/main/resources/places/README.md). The OpenStreetMap shapes are generated by the
scripts in `tools/geo/`; the residents (`places/population/*.csv`) are edited by hand, each figure with its date and
source. A file of the same path in `./config/places/` where the backend runs overrides the built-in one.

### Vehicle and line classes

Configured in two YAML files, not in code: `backend/src/main/resources/classification/vehicles.yml` and `routes.yml`
(each explains its rules at the top). A copy in `./config/classification/` where the backend runs overrides the
built-in one without rebuilding; restart to apply.

- **Vehicle class** (`vehicle.vehicle_class`): `mini` (under 8 m), `midi` (8 to 10.5 m), `maxi` (12 to 14.9 m),
  `mega` (15 m and more) or `double_decker` (`DD` in the model name). A model's length comes from its entry in
  `vehicles.yml` (the first whose pattern is found in the model name), else from the name ("Urbino 15", "10.6M").
  Lengths between 10.5 and 11 m count as midi, 11 to 12 m as maxi. Each entry may give the **capacity**
  (passengers, seated + standing), with its source: manufacturer or registration figures, which differ between
  operators' configurations; models without a reliable figure have none. Applied at startup and to every newly
  registered bus; a capacity entered by hand is kept when the model has none.
- **Line geometry** (`route.geometry`, the configured operators' lines only, i.e. BKM): from the line's main route
  shape and the city centre as set in `routes.yml` (the core around Rynek Kościuszki, Lipowa, Sienkiewicza and the
  Palace, up to al. Piłsudskiego; smaller than Osiedle Centrum: the intersections around Wzgórze św. Rocha, Poleska
  and Bohaterów Monte Cassino aren't the centre; a line passing within 100 m of it goes through it): `radial` (an end
  in the centre or at the railway station, a central terminus), `diameter` (through the centre, ends at least 143°
  apart seen from it), `arcantial` (through the centre, ends 30°–143° apart), `tangential` (not through the centre,
  swinging at least 60° round it, e.g. line 23) or `feeder` (not through the centre, ends on the same side); `overrides` for lines the
  measures get wrong (line 26: a diameter).
- **Operating company** (`route.operating_agency`): KPKM, KPK or KZK, from `classification/operators.yml` (BKM is
  the organiser, `route.agency`); stored when lines are classified.
- **Line capacity** (`route.capacity_class`; not for night lines, which get a geometry only — `skip-subtypes`): from
  the weekday departures (lines without weekday service: the service they run most) and the share of the line's recorded reports made by MEGA buses over the last 28 days, per day type. Ordered rules:
  `main` (MEGA every day, peak headway under 15 min), `primary` (MEGA on some weekday runs, under 20 min), `secondary`
  (average headway under 60 min), `tertiary` (at least 8 trips per direction with fairly even gaps), else
  `irregular`.
- Lines are classified at startup (in the background), after every timetable import and nightly at 03:30. Per-line
  `overrides` in `routes.yml` set a class outright.

### Timetable (GTFS) import

`POST /api/gtfs/bialystok/import`, or **Settings → Import Białystok timetable** in the app (admins only), downloads
the feed and loads agency, routes, stops, trips, stop times, calendar dates and shapes. It takes about 30 s, mostly
inserting the ~384k stop times.

- The tables follow GTFS (migration V7). Each row keeps its feed id in `gtfs_id`; times stay as GTFS `HH:MM:SS` and can
  go past 24:00 for trips after midnight. Route colours are stored as `#RRGGBB`.
- Everything happens in one transaction: the app keeps showing the old timetable until the new one is complete, and a
  broken feed changes nothing (422). If the download fails the answer is 502; a second import while one runs gets 409.
- Agencies, routes, stops and trips are updated in place by `gtfs_id`, so their database ids stay the same across
  imports (and links to them keep working). Stop times, calendar dates and shape points are replaced.
- Each route has a `subtype`: `urban`, `suburban` (numbers from 100 up), `night_urban` (N1, N2, …) or `regional`
  (400–499 and 700, or set by hand). New routes get it from their name (`RouteSubtype.classify`, migrations V10, V12
  and V13); an
  import keeps the subtype of existing routes, so a manual change stays.
- Rows that point at a route, trip or stop that isn't in the feed are skipped and counted in the response. Files the
  app doesn't load (e.g. `feed_info.txt`) are listed as ignored.
- Code: `gtfs/GtfsImporter` (generic loader: files are streamed from the zip into temporary tables with PostgreSQL
  `COPY`, then merged with SQL) and `gtfs/GtfsFeedService` (download + import of each feed).

**Other operators' lines** (`app.gtfs.extra` in `application.yml`): each feed is loaded into its own share of the
timetable. Every timetable row has a `feed` column (`bialystok` for the city's, else the key below) and keeps its ids
as the feed gives them; feeds reuse each other's ids (stop `536`, service `319`, agency `1`), so ids are unique per
feed and the import looks them up within the feed. An import replaces only that feed's rows (the city's import leaves
the others alone too). Imported with `POST /api/gtfs/<key>/import` and again every night at 04:50
(`extra-refresh-cron`), as these feeds cover about two weeks at a time.

- **Shared stops:** these feeds give a city stop their own id and BKM's id as its code (Wschód Express's stop `536`,
  code `305`, is BKM's al. Piłsudskiego/Rzeka Biała, `305`). A stop whose code is a BKM stop id within 60 m of it is
  BKM's stop: the line's trips stop there and no copy is loaded. Re-importing BKM's timetable keeps its stops (and
  their database ids); a BKM stop that leaves its timetable takes the other feeds' stop times there with it until
  their next import.
- `pksnova-500`: **PKS Nova line 500** (Białystok D.A. ⇄ Supraśl), `https://files.girlc.at/gtfs/pks_nova_suprasl.zip`
  (Transitland `f-u92~pks~nova~suprasl`). Agency "PKS NOVA S.A.", route `500`, a suburban line (from its number),
  about 50 trips a weekday; 15 of its 40 stops are BKM's. It isn't on the live feed (zbiorkom.live), so nothing is
  recorded for it.
- `wschodexpress`: **Wschód Express**, regional lines 400–476 run for the gminas Dobrzyniewo Duże, Zabłudów,
  Trzcianne and Krypno (five agencies), `https://files.girlc.at/gtfs/wschod_express.zip`; 98 of its 574 stops are
  BKM's. Its buses are on the live feed as `WSCHODEXPRESS`.
- `turosn-koscielna`: **Turośń Kościelna line 700**, `https://files.girlc.at/gtfs/turosn_koscielna.zip`; 7 of its 60
  stops are BKM's. Its buses are on the live feed as `TUROSN`.
- All three are unofficial feeds generated from Time4Bus (CC0). `agency` in each feed's settings names the operator
  its buses have on the live feed, so a line's recorded runs (travel times, delays on the Routes page) are its own.
- On the Routes page such a line shows like the city's (map, variants, termini, bus lanes, zone 2, travel times),
  run by its own operator, at `/routes/route/<number>` (a city line wins if a feed ever had the same route id). What
  the feed doesn't have is said rather than guessed: without brigades there are no brigades or depot runs; trips
  without a direction get one from where they start; stops without a fare zone are zone 1 inside the city limits and
  2 outside. Its timetable is the weekday the line has the most trips (its feed has a service per date). Line classes
  (geometry, capacity) and Network coverage are the city's lines only. On Brigades each feed is one operator (key: the feed,
  e.g. `wschodexpress`, shown by its short name; Wschód Express's five agencies are the municipalities ordering its
  lines, and one bus runs lines of two of them; a former `wschodexpress-1` link opens it), and a feed whose buses aren't on the live feed (no `agency`: PKS
  Nova) is left out of Abnormal trips, as nothing can be recorded for it. The Stops page shows each stop's timetable.
- **Brigades learnt from the recordings** (`service/RecordedBrigades`, `BrigadeLearning`): these feeds name no
  brigades (no block ids), but their buses report brigade numbers on the live feed. The numbers repeat from line to
  line ("1" on line 400 and "1" on line 414 are different buses), and a bus runs some lines together under one number
  (414, 415 and 416). So a brigade is a number on a group of lines (the lines one bus ran under it on some day,
  joined), named after the group's first line: `414-1`, `451-20`, `700-T1`. Each recorded report is matched to its
  line's trip by time (as Abnormal trips does), and a trip was run that day by the bus and number with most reports on
  it (3 at least). The feeds give every date its own trips, so what is learnt is the brigade of a *run* (the line's
  trip between two stops at a time of day, on a weekday, Saturday or Sunday): the brigade that ran it on most of the
  last 28 days. Every trip of that run, also on the dates ahead, belongs to it (`trip_brigade`); each live number on a
  line has its brigade (`recorded_brigade_line`), so reports are read with the timetable's brigades. Learnt again
  every night (`app.recorded-brigades.refresh-cron`), after a feed's import and at startup. With them these lines
  have brigades as the city's have: on Brigades (a brigade's day, scheduled and run), Abnormal trips, and the line's
  brigades and depot runs on Routes. A run never seen run (e.g. Wschód Express lines 404, 468–470, which no bus
  reports) has no brigade; the live map's brigade popups still name the live number.

### Vehicle position history (time series)

Every 10 seconds, all day and night (the night lines run 00:10–04:06 on weekend nights), **transit-recorder** polls the zbiorkom.live Białystok feed and
stores every vehicle's position in `position_sample`, a TimescaleDB hypertable. It also keeps the raw response,
gzipped, in the archive (`backend/data/raw/zbiorkom-bialystok/YYYY/MM/DD/<UTC time>.json.gz`).

#### The recorder (transit-recorder)

The feed only ever gives current positions, so any moment nothing polls it is lost for good. Polling therefore runs in
its own small app, `backend/recorder` (Spring Boot, JDBC only, no schema of its own), not in the backend: restarting or
deploying the backend loses nothing.

- **Each poll:** fetch the response; archive it first, as received; then register vehicles seen for the first time
  and store the positions. A report seen twice (same vehicle and feed timestamp) is stored once.
- **Database down:** the archived response is listed in the spool (`backend/data/spool/pending`) and stored once the
  database is back (every 30 s and at startup), oldest first. Nothing is lost while the recorder itself runs.
- **Live map:** the backend takes the recorder's latest snapshot (`GET http://localhost:8081/live/bialystok/positions`,
  `RECORDER_URL`), adds each line's kind and serves it. When the recorder doesn't answer within 2 s, or its snapshot is
  over a minute old, the backend asks zbiorkom.live itself. Outside recording hours the recorder fetches on demand (at
  most every 10 s, not stored).
- **Database access:** it logs in as `transit_recorder` (migration V16: add vehicles and positions, read them back),
  password `RECORDER_DB_PASSWORD` (the same for the backend, which sets it, and the recorder). The backend owns the
  schema; the recorder checks the tables exist at startup. New buses get their size class from the backend within
  5 minutes (`VehicleClasses.applyToNew`).
- **Running it:** `backend/recorder/upgrade.sh` (also the first time; needs Docker access). It builds the image, creates
  `backend/data/raw` and `backend/data/spool` (the container runs as you; folders Docker creates itself belong to root
  and the recorder couldn't write there), and stops a recorder running outside Docker once the container records, so
  port 8081 is free. Docker restarts it after a crash or reboot (`restart: unless-stopped`). Health with details (last poll, last stored,
  responses waiting) at `http://localhost:8081/actuator/health`; it turns DOWN after a minute without a stored poll
  during recording hours. Without Docker: `./gradlew :recorder:bootRun` in `backend/`.
- **Upgrading without a gap:** the same script builds the new version, starts it next to the running one (port 8082),
  waits until it records, then replaces the old one. Both poll for a while; the duplicate reports are stored once. Or
  upgrade between 01:30 and 03:30 on a weekday night, when no buses run.
- **Monitoring:** the same metrics as before (`transit.recording.samples`, `.failures`, `.vehicles`, `.poll`), plus
  `transit.recording.spooled`; service name `transit-recorder` in Grafana.
- **Excluded lines:** `app.zbiorkom.bialystok.excluded-lines` (a regular expression on the line name, in both apps'
  `application.yml`) lists lines left out everywhere: not stored, not archived (removed from the response before it
  is saved) and not shown on the Live map. Set to `^C[0-9]+$`, the cemetery lines run around All Saints' Day; their
  recordings, and every recording of 31 October and 1 November 2026, were deleted from the database and the archive.
- **Settings** (`recorder/src/main/resources/application.yml`): `RECORDING_ENABLED`, `cron`, the window,
  `min-interval` (8 s), `RECORDING_ARCHIVE_DIR`, `RECORDING_SPOOL_DIR`, `DB_URL`/`DB_USER`/`DB_PASSWORD`, port 8081.
- **Code:** `backend/zbiorkom-feed` holds what both apps use (feed client and payloads, parsing positions and
  registering vehicles, archive, position writer); `backend/recorder` the polling, spool, live endpoint and backfill.

- **Why PostgreSQL + TimescaleDB.** Half a year is about 25 million rows (≈ 940k vehicle-minutes per week from the
  timetable; weekday peak 181 buses). That's small for PostgreSQL, and it keeps positions next to the GTFS and vehicle
  tables for joins. TimescaleDB adds weekly partitions, columnar compression after 7 days, self-updating aggregates,
  and statistics functions (`timescaledb_toolkit`).
- **Expected size for half a year at one poll per minute:** ~2 GB of gzipped archive (13 KiB per snapshot), ~6 GB in
  PostgreSQL uncompressed (measured ~230 bytes per row with indexes), ~0.7 GB once compressed (measured 8.6× on a real
  week of data). Polling every 10 seconds is about six times that (~1 million rows and ~100 MB of archive a day):
  most vehicles send a new report every few seconds, so nearly every poll stores a new row per vehicle.
- **Polling:** every 10 seconds (`cron`), never closer than 8 s (`min-interval` guard). Recorded every minute until
  November 2026, then every 20 seconds, then every 10.
- **Vehicles running** at a poll are the vehicles that reported in the minute up to it, so a vehicle that skips a poll
  still counts, and the figure doesn't depend on the polling interval.
- **Aggregates (refresh in the background, include the newest rows):**
  - `fleet_5min`: vehicles in service and mean delay per 5 minutes.
  - `route_delay_hourly`: per route and hour, `delay_stats` (mean/stddev via `average()`, `stddev()`) and
    `delay_pct` (percentiles via `approx_percentile(0.9, …)`). Combine buckets with `rollup()`.
- **Grafana:** the **Transit Fleet** dashboard shows vehicles in service, delay over time, a route × hour delay
  matrix, the most delayed routes, outliers (> 3σ from the route's mean for that hour), and a map of the vehicles at
  the end of the selected time range. It reads the database as `grafana_reader`, which can't see users or passwords.
- Positions are only recorded while the recorder runs. Turn recording off with `RECORDING_ENABLED=false` (recorder).
- Vehicles with recorded positions can't be deleted (the history references them).

**Backfill saved snapshots** (one API response per file, `.json` or `.json.gz`, any folder depth). The command
imports them into `position_sample` and the archive, refreshes the aggregates for that period, and exits. Running it
twice adds nothing. It runs as the database owner, since refreshing the aggregates needs more than the recorder's role.

```bash
cd backend
DB_USER=transit DB_PASSWORD=transit ./gradlew :recorder:bootRun \
  --args='--app.recording.import-dir=/path/to/snapshots --server.port=0'
```

**Analysis examples** (psql: `psql -h localhost -U transit transit`):

```sql
-- Mean and p90 delay per route on weekdays, by hour of day
SELECT route_name, extract(hour FROM bucket AT TIME ZONE 'Europe/Warsaw') AS hour,
       average(rollup(delay_stats)) AS mean_s, approx_percentile(0.9, rollup(delay_pct)) AS p90_s
FROM route_delay_hourly
WHERE extract(isodow FROM bucket AT TIME ZONE 'Europe/Warsaw') <= 5
GROUP BY 1, 2 ORDER BY 1, 2;

-- One vehicle's track for a day
SELECT observed_at, latitude, longitude, delay_seconds, route_name
FROM position_sample p JOIN vehicle v ON v.id = p.vehicle_id
WHERE v.vehicle_number = '230' AND observed_at >= '2026-11-16' AND observed_at < '2026-11-17'
ORDER BY observed_at;

-- Storage used, before and after compression
SELECT * FROM hypertable_columnstore_stats('position_sample');
```

For projections and modelling, connect pandas or a notebook to the same database. For heavy offline work, export a
period to Parquet and use DuckDB.

**Switching from the old `postgres:17` container.** The TimescaleDB image uses a new volume (`tsdata`), so it starts
empty. Flyway recreates the schema and users; vehicles re-register from the feed; re-import the timetable from
Settings. The old data stays in the `pgdata` volume until you remove it with `docker volume rm transit-analytics_pgdata`.

Interactive docs: Swagger UI at http://localhost:8080/swagger-ui.html, OpenAPI JSON at `/v3/api-docs`. To try
the secured endpoints, call `POST /api/auth/login`, copy the `token`, click **Authorize** and paste it. Set
`API_DOCS_ENABLED=false` to turn both off (e.g. in production).

Access rules: reads need ADMIN or GUEST; writes need ADMIN; `/api/users` and `/api/roles` are ADMIN only.

Adding a new entity: migration `V<n>__*.sql` → entity in `domain/` → repository → DTO record → service extending
`CrudService` → controller extending `CrudController`.

### Tests

```bash
./gradlew test                                  # unit + web-slice tests, no DB needed
INTEGRATION_TESTS=true ./gradlew test           # also boots the full context against the DB (startup warm-ups and street routing off)
./gradlew check                                 # tests + coverage floor
```

Every test run writes a JaCoCo coverage report to `build/reports/jacoco/test/` (`html/index.html`, and
`jacocoTestReport.xml` for CI). Unit tests alone cover about 54% of lines; with the database tests about 95%.
`check` fails below 50% of lines, or below 90% when `INTEGRATION_TESTS=true` (as in CI).
Database tests use `@DbIntegrationTest` and roll their data back; `TimetableFixture` inserts a small timetable.
They run against their own database, `transit_test` on the same server (`TEST_DB_URL` to change it; CI points it at
its own `transit`), never the one the recorder writes to. Roles belong to the whole server, so the tests keep their
passwords while migrating (`SharedRoles`). Create it once:

```bash
PGPASSWORD=transit psql -h localhost -U transit postgres -c "CREATE DATABASE transit_test"
PGPASSWORD=transit psql -h localhost -U transit transit_test -c "CREATE EXTENSION IF NOT EXISTS timescaledb"
```

## Frontend

- `core/auth` — `AuthService`, `authInterceptor` (Bearer token, logout on 401), `authGuard`, `roleGuard`, `*appHasRole`
- `core/api` — generic `CrudApiService` + one service per backend resource
- `layout/shell` — the sidebar (pages grouped into *Operation* and *Network*, each with a filled Bootstrap icon from
  `shared/icons`) and the header bar with the page's title (from the route's `title`, see `core/title/page-title.ts`)
  and the hamburger menu (Settings, Data sources, Logout, each with an icon). On a desktop the icon at the right of the sidebar's header narrows it to its
  icons (names as tooltips; remembered in the browser; `layout/shell/sidebar.ts`). Up to 768 px wide (phones, small tablets) the sidebar is hidden to the
  left and shown over the page with the button at the left of the header bar; choosing a page, tapping beside it,
  its ‹ button or Escape hides it again. Up to 600 px the time window's presets are a dropdown, the From/To fields
  stack, and figure tiles sit two to a row.
- Theme: light by default, dark when chosen in Settings (remembered in the browser; `core/theme/theme.ts` sets
  `data-theme` on `<html>`). The colour tokens are at the top of `styles.scss`: Bootstrap's greys, the primary teal
  `#00a9bc` (sidebar, focus, chosen buttons; the open page 20% darker), and one radius (8 px) and shadow for cards,
  inputs, buttons, charts and maps.
- Errors: every 4xx or 5xx answer from the API (and a server that can't be reached) shows as a toast in the bottom
  right corner, with the backend's message (`core/api/error-toasts.ts`, ngx-toastr); pages never show the error text
  themselves. `errors-as-toasts.spec.ts` opens every page with the API failing to check that. ngx-toastr 20 declares
  Angular 21; `overrides` in `package.json` lets it run on Angular 22 until a release supports it.
- Languages: English (default), Polish, German and French, chosen in Settings from a dropdown of flags and native names,
  remembered in the browser and applied at once (Transloco). The texts are in `src/i18n/{en,pl,de,fr}.json`; English is
  built in, the others load from `/i18n/` when chosen. Templates and code translate with `t('key', params)` and
  counts with `tn('key', n)` (`core/i18n/t.ts`, plural forms per language); the `date` and `number` pipes follow the
  language (`shared/i18n/locale-pipes.ts`). `translations.spec.ts` checks every language has every text, the same
  placeholders and all its plural forms. Texts from the backend (breakdown stories, trip notes, error details) and
  data names stay as they are.
- Loading: while a page waits for its data it shows skeletons shaped like what is coming (tiles, charts, maps,
  tables; `shared/skeleton`, ngx-skeleton-loader, in the theme's colours), and a thin bar runs under the header while
  any API request is in flight (`core/api/activity.ts`; after 300 ms, so quick answers don't flicker; the live map's
  polling doesn't count).
- `store` — NgRx slices with functional effects: `auth`, `livePositions`, `routes` (all routes), and
  `stops`, `trips`, `stopTimes`, which hold one server-side page each (shared helpers in `store/paged.ts`);
  `gtfsImport` tracks the import. Only `auth` is registered at startup; every other slice is registered by its
  page's lazy-loaded route (`*.routes.ts`), so it loads with the page.
- `shared` — pieces several pages use: `app-time-window` (the time window and operators of every report page, see
  below), the time-series, hour-of-day and bar charts, the timeline styles, `createBaseMap` (Leaflet + OSM),
  `hostWidth` (chart width), `injectSetQuery` (merge changes into the URL's query parameters), time and delay
  formatting, and the colour palette (`cssColor` reads theme tokens).
- Time window: every page over recorded positions (Fleet, Home's recorded positions, Stop hotspots, Delay
  generation, Abnormal trips) has the same control: from/to pickers in the first row, then buttons for the last
  1 min, 5 min, 30 min, 1 h, 2 h, 12 h, 24 h, 3 days, 1 week, 1 month and all time, a dropdown of checkboxes for the
  operators where the page can filter by them (Fleet), and the page's own filters. It is kept in the URL
  (`?range=1w`, or `?from=…&to=…` in local time, and `?agency=`).
- Startup bundle: telemetry (the OpenTelemetry SDK) loads as its own chunk right after startup, and the NgRx
  devtools are only in development builds (`store-devtools.prod.ts` replaces them in production).
- `pages` — login, settings (theme; versions; GTFS import for admins), sources (where the data comes from; "Data sources" in the header menu), and:
- Home: buses with a possibly faulty tracker first (a tile with a red border while any bus is listed: each bus links
  to its page, with its no-signal trips of all it ran, the days, its lines and the latest), then the live map
  (Leaflet/OSM; a `livePositions` load every 5 s, markers drawn from the store). Each bus is a pill-shaped badge with
  its line number in its kind's colour, an arrow pointing its way when the feed gives a bearing, and a ring in the
  delay's colour when over a minute late (none on time, up to a minute late, or not known). Its own filters by
  operator, kind of line and delay, in the URL as `?mapAgency=`, `?mapLineType=` and `?mapMinDelay=`, with a reset;
  the popup shows the brigade and, folded away under it until clicked, its remaining runs today (kept open or
  folded as the popup redraws every 5 s); the city limits and centre are drawn under the buses. It
  always shows now: no time window changes it.
  Below it, the recorded positions with their own time window, operator (`?agency=`, BKM by default, `all` for every
  operator) and line: vehicles running and mean delay every 10 s (each poll) for windows up to an hour, else every
  minute or coarser (SVG line charts with a hover/keyboard tooltip and a table view), running now / most at once /
  different vehicles seen, and the delay distribution in four bands (on time, more than 2, 5 and 10 min). Preset
  ranges refresh with each poll (up to 1 h) or every minute; a change of window loads at once. Store slices
  `livePositions` and `positionHistory`, registered by Home's lazy route. The former pages `/live-map`,
  `/positions` and `/delays` lead to Home. The former Line statistics page (`/lines`) leads to Routes, where each
  line's regularity is a tile.
- Routes: every line in a table of kinds of line (rows: urban, suburban, night, regional) by geometry (columns:
  radial, diameter, arcantial, tangential, feeder, and not classified for other operators' lines); a line's button
  shows its capacity class by its border's thickness and its fill (strongest for main lines, dashed for irregular
  ones), with a legend. Under it, eight tiles in two rows rank the lines, a top 10 from either end: length, speed in
  peak and off-peak hours, regularity in peak hours, delay (recorded, last 28 days) and frequency in peak and
  off-peak hours. Choosing a line opens its page, `/routes/route/103` (a former `?line=` link leads there too; title "Route 103", a way back at the top;
  Corridors links there too); its title says what its geometry and
  capacity class mean and links to its trips and its timetable on the operator's website. Regional lines of other
  operators aren't in the timetable: for them the map shows where their buses actually went, from every recorded
  position (each bus's reports split into runs towards one destination, and runs along the same streets grouped
  into variants: the most driven first, rare ones thin and dashed, listed with how often and when last driven), with
  the delay over the day and the buses. The map shows the base
  route thick and other variants thin and dashed in the same colour, with zone-2 stops filled. Below: length,
  scheduled travel time and speed, zone 2, the variants, charts of measured travel time, speed and delay over the
  day (each with a table view), speed between stops in peak hours with the slowest stretches numbered, and a
  timeline of the brigades with their breaks. Depot runs are yellow and labelled everywhere, the standard end stops
  and depot-run stops are listed under the title, and a Vehicles section lists the buses and brigade changes.
  Store slice `routeDetail`, registered by its lazy route.
  Under the title: who runs the line — BKM organises, and one of three companies operates it (KPKM, KPK or KZK; see
  `classification/operators.yml`, from the BKM contact page) from its depot. The map draws the city limits and the
  centre lines are classified by, every depot's area (OpenStreetMap outlines in `map/depots.yml`: KPKM and KPK on
  ul. Składowa, KZK on ul. Pomocna; the operator's darker and named), and dashed yellow lines along the streets
  between the operator's depot and each place where the line's brigades start their day (the first stop of their
  first trip) or end it (the last stop of their last trip): the shortest drive from an OSRM server (the public demo
  by default, `app.map.routing` in `map/depots.yml`; only coordinates are sent; remembered while the app runs;
  straight when it can't answer). The detail API gives `operator`, `depots` and `depotLinks` (each with its `path`).
- Important corridors: two maps of every stretch between stops by its buses in peak hours (one by line width, one
  by colour in equal classes of a sequential blue), then the corridors above in a sortable table (busiest first), with each corridor's stops in an
  expandable list and its lines linking to Routes. Store slice `corridors`, registered by its lazy route.
- Fleet (`/vehicles`; `/fleet` leads there, and a former `?vehicle=7` link to the bus's page): tiles with the buses in use, the fleet by model, by size class (smallest first) and by make (the model
  name's first word) — bars of in use / in the fleet, the 10 most and least used
  buses and the buses that broke down (when, and how many days until they were back, or that they aren't yet); a
  bar chart of every bus's hours, the most used first; and a search by number or model (title "Fleet Overview"). A
  chosen bus opens its page, `/vehicles/7` (its id; title "Vehicle 230", its number; the time window kept; the way
  back is a chevron at the left of the header), with its runs and breaks as a timeline (a row per day, or per run for a window of a day or less), its hours per
  day (days with an abnormal trip marked) and the abnormal trips it broke down on or missed. Each breakdown is told
  step by step (open at first, folds away): a time line of the bus and its replacement (a lane per bus: on the trip,
  standing still, moving off or on its way without a brigade, on the brigade; the steps numbered on its axis), then
  the numbered steps in words, then a map of where both buses went over that time (the one that broke down in
  orange, ringed where last seen; the one that took over in blue). Store slice `fleet`,
  registered by its lazy route.
- Stop hotspots: where buses lose time standing still away from stops (traffic lights, junctions, congestion), as
  circles on a grey map sized by the time lost, and a table of the hotspots, the most time lost first. Pick the time
  window and one line or all (kept in the URL with `?line=`). Click a row to show
  its hotspot on the map (with its spread), or a circle to find its row. Store slice `hotspots`, registered by its
  lazy route.
- Bus lanes: how long they are (OpenStreetMap against the city's figure); a map of where they are, each section
  violet when buses have a lane one way and orange both ways (on one section, or on both carriageways), with arrows
  the way each lane runs from zoom 15; how buses fare on bus lanes, on the same streets the other way and elsewhere
  (speed, weekday peak and off-peak, delay change per km); a map of the lanes coloured by speed; speed and delay
  change by weekday hour; and a sortable table of each lane (street and direction) against the same street the
  other way. Store slice `busLanes`, registered by its lazy route. Bus lanes are also drawn (a translucent violet
  band) on the live map (Home) and the Routes map, and Routes says how much of the line runs on them each way.
- Delay generation: a map of every stretch between stops coloured by the delay buses gain on it (red) or recover
  (blue), both directions side by side, for the whole day or a part of it; the stretches creating and recovering
  the most delay altogether; and each line's gain per run. Click a line for, per direction, a table of its stretches
  in route order with the change by hour of the day (a heatmap with the numbers in the cells), the runs that gained
  the most (overall or on a chosen stretch) with a chart and table of how their delay built up stop by stop, and
  its brigades. Window, part of the day and line are kept in the URL (`?part=`, `?line=`). Store slice
  `delayGeneration`, registered by its lazy route.
- Brigades: every brigade of an operator (BKM by default; `?agency=`, e.g. `wschodexpress`; other operators by their short name) in rows by line, with a search by name or line.
  Choosing one opens its page, `/brigades/103-05` (a former `?brigade=` link leads there too; title "Brigade 103-05"; the way back is a chevron at the
  left of the header), with only the day (`?date=`, today's service day by default) at the top. Tiles sum the
  day up; a timeline shows every scheduled trip in turn (consecutive trips alternate BKM light blue and green, labelled with
  time, line and destination) and, under each, what actually ran: a bar per bus labelled with the bus and how late it
  set off, a dashed line from the scheduled to the actual start, parts not run hatched red, parts the bus wasn't seen on
  hatched grey, markers for a bus change, a lost trip and a detour, and outlines for trips without data or still to
  come. Hover a trip for details; a table lists every trip with the same.
- Network coverage: for each walking distance (400, 500, 600 m), tiles with the share of the city within it of the
  nearest bus stop, with and without the woods and the Biała valley, and a map (rolls up to its title) of the area
  near a stop with the woods hatched green and the valley hatched light blue; the woods and the valley's stretches
  listed. Then the population density: one map of the districts and the villages and towns around the city on one
  scale (density outside the woods and the valley; those as nobody living there; places without a figure left
  white), and tables of the districts, the districts with large woods or valley, and the belt.
- Abnormal trips: the trips of any time window (preset windows up to 12 hours refresh every minute). Cards count the trips by outcome (click a kind to list only those), a table shows the
  lines with abnormal trips, and the trips table lists each abnormal trip with its kind, scheduled times, line and
  brigade, buses, the stops it was seen on, its delays and what happened (filter by line, or by bus). Bus changes show
  where the new bus came from and whether the old one was possibly broken down or reassigned, and how long a broken
  one stood and what it did next. Each breakdown is told step by step, with a time line, on the bus's page under
  Fleet. Each detour gets a map on a line of its own under its trip: the usual route around it in blue, the bus's
  path in orange with its reports as dots. Store slice
  `tripAnomalies`, registered by its lazy route.
- Trips: every trip scheduled in the time window (the last hour by default; preset windows up to 12 hours refresh
  every minute), 50 a page, each on one time axis shared by the list: the scheduled bar over what ran, coloured by
  the bus's delay along the way (on time, 3, 6, 10+ min), parts not run hatched red and not seen grey, where the bus
  stood still 3+ min outlined, and marks for a change of bus (⇄), a turn short of the end (↩), a lost bus (✕) and a
  detour (↷). A search box finds a bus, a brigade, a line or a destination (`?q=`). Opening a trip (`?trip=`) shows it
  on its own axis with the bus's delay and its speed over the whole trip in two charts (the delay per minute; the speed, once the trip's detail is in, from
  every report, so a halt shows as a drop to 0) (hover for the values at a
  moment), its average speed, its longest halts at stops, what happened on the way in words, and a link to the brigade's day. Each stop is a
  tick on the scheduled bar (when due) and a dot on the delay and speed lines (where the bus passed it); hovering one
  shows its name, times and how long the bus stood there, and a guide down the charts, so it's clear between which stops delay grew or the bus stood. A Map button beside the actual bar
  (`?map=1`) shows the trip on a map: its route in grey and the bus's path over it, coloured by its delay the same
  way (hover a stretch for the time, bus and delay), its stops (hover one for its name, when it was due and when
  the bus passed it, and how long it stood there), and where it set off and was last seen. Store slice `tripRuns`, registered by
  its lazy route. Its time lines are pure functions in `trip-layout.ts`. The former timetable list of trips is gone;
  Routes links a line's trips here (`/trips?q=103`).
- Line labels everywhere (Routes, its map and table, Corridors, Stop times, Abnormal trips, Delay generation,
  Brigades, a bus's page, Trips, the live map) take their kind's colour: indigo urban (#4b0082), green suburban
  (#03bf03), blue regional (#0021ff), black night lines (`--line-*` in `styles.scss`; `LineBadge` and `LineKinds` in `shared/line-kind`, which
  ask `/api/routes/subtypes` once and classify a line not in the timetable by its name).
- Timetable pages (read-only): Stop times of a trip; Stops (search, zone) → departure board of a stop. Filters and the page number live in the URL, so every view can be
  linked and the back button works.

```bash
npm start        # dev server with API proxy
npm test         # Vitest unit tests
npm run test:coverage   # once, with coverage of all of src/app (report in coverage/frontend/index.html);
                        # fails below the thresholds in angular.json (test options)
npm run build
```

## Versions

Two, each raised on its own (semantic versions: patch for a fix, minor for a feature, major for a change that needs
something done by hand):

| Version | Of | File |
|---|---|---|
| app | backend and frontend, released together | `VERSION` (and `frontend/package.json`) |
| recorder | transit-recorder | `backend/recorder/VERSION` |

Each file has the version and its release date (the day it was set). `tools/version.sh` shows both; `tools/version.sh app 1.1.0` or `recorder 1.0.1` sets one; CI runs `check`, which fails
when the files disagree. A change in `backend/zbiorkom-feed` is in both apps, so it raises both. A release is a tag on
`main`: `app-v1.1.0` or `recorder-v1.0.1`. The sidebar shows the app's version; Settings both with their release dates, and tells when the server has a newer
one than the open page; each app also has its own in `/actuator/info` (the backend's needs a sign-in).

## Hosting

A server runs released images with `docker-compose.prod.yml`: PostgreSQL, transit-recorder, the backend, and Caddy with
the frontend (`frontend/Caddyfile`). Only Caddy's ports 80 and 443 are public; it serves the pages and passes `/api` on
to the backend. No telemetry is sent. A release's tag builds the images (`.github/workflows/release.yml`), for ARM and
x86, as `ghcr.io/twardzikfilip/transit-{backend,frontend,recorder}:<version>`; new packages are private until made
public on GitHub.

With `ENVIRONMENT=production` (set there) the backend doesn't start with a secret or password from the repository, and
gives `admin` and `guest` the passwords in `.env` (`ProductionSecrets`). Ten failed logins from one address within 15
minutes make it wait (429, `LoginAttempts`). The recorder's health is DOWN when nothing was stored *or archived* for
a minute.

On the server: clone the repository, make `.env` with `tools/server-env.sh <domain>`, create `backend/data/raw` and
`backend/data/spool` (owned by `RECORDER_UID`), then `docker compose -f docker-compose.prod.yml up -d postgres` and restore
the data **before** starting the recorder. Later:

- app: set `APP_VERSION` in `.env`, then `docker compose -f docker-compose.prod.yml up -d --no-deps backend frontend`;
- recorder: `backend/recorder/upgrade.sh 1.0.1`, which leaves no gap. Never a bare `up`, which could recreate it.

## Continuous integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every pull request and on pushes to `main`:

- **Backend:** `./gradlew check` with the database tests, against a TimescaleDB service container (the same image as
  `docker-compose.yml`; Flyway builds the schema on the empty database). Fails on a failing test or line coverage
  below 90%.
- **Frontend:** `npm ci`, `npm run test:coverage` (fails below the thresholds in `angular.json`) and the production
  build (fails over the bundle budget).

Test and coverage reports are kept as artifacts of each run for 14 days.

## Observability

Both apps send OpenTelemetry data over OTLP/HTTP. Locally, the `otel-lgtm` container receives it and shows it
in Grafana at http://localhost:3000: Tempo for traces, Prometheus for metrics, Loki for logs.

| | Backend (`transit-backend`) | Frontend (`transit-frontend`) |
|---|---|---|
| Traces | every HTTP request, with JDBC connection and query spans (result-set reads aren't traced: that made large reads 8× slower) | page load, every fetch/XHR, route changes, login attempts (`auth.login`), uncaught errors (`ui.error`) |
| Metrics | HTTP server, JVM, Hikari pool, `transit.auth.logins{outcome,role}` | Core Web Vitals `web_vitals.{lcp,inp,cls,fcp,ttfb}` by route |
| Logs | exported over OTLP with `trace_id`; console lines show `[traceId-spanId]` | — |

Telemetry is kept in the `lgtmdata` Docker volume, so it survives restarts and recreating the container. To clear
only the telemetry, run `docker compose rm -sf otel-lgtm` and then `docker volume rm transit-analytics_lgtmdata`.
Don't use `docker compose down -v`: it also deletes the Postgres volume.

Dashboards in `grafana/dashboards/` are loaded into Grafana automatically, in the **Transit Analytics** folder.
- **Transit Backend** lists every backend request (time, method, endpoint, status, duration, who started it).
- **Transit Logins** counts successful and failed logins in the frontend and lists each attempt (time, user, role,
  outcome).

Each row links to its trace. To add a dashboard, drop its JSON into that folder; Grafana picks it up within about
10 seconds.

A browser API call and the backend request it triggers share one trace: the frontend sends a W3C `traceparent` header.
No headers, tokens, passwords or request bodies are recorded. The one exception for personal data: each `auth.login`
span carries the username (`user.name`), so stored traces show who logged in and when. Usernames never appear in
metric labels.

Backend settings:

| Setting | Env var | Default |
|---|---|---|
| OTLP/HTTP base URL | `OTLP_ENDPOINT` | `http://localhost:4318` |
| Trace sampling ratio | `TRACING_SAMPLING` | `1.0` |
| `deployment.environment` | `ENVIRONMENT` | `local` |

The frontend's settings are in `frontend/src/app/core/telemetry/telemetry.config.ts`. In development, `/otlp` is
proxied to the collector (`proxy.conf.json`). In production:
- serve `/otlp` through your reverse proxy to a collector, or point `endpoint` at a collector that allows CORS
- set `propagateTraceHeaderCorsUrls` if the API is on another origin
- lower `sampleRatio` and `TRACING_SAMPLING`
