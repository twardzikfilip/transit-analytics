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
| Białystok bounding box (`minLon,minLat,maxLon,maxLat`) | `ZBIORKOM_BIALYSTOK_BBOX` | `22.921150,53.016270,23.382496,53.243687` |
| Białystok GTFS feed (zip) | `GTFS_BIALYSTOK_URL` | `https://komunikacja.bialystok.pl/cms/File/download/gtfs/google_transit.zip` |
| transit-recorder address (Live map) | `RECORDER_URL` | `http://localhost:8081` |
| Password of the recorder's database role | `RECORDER_DB_PASSWORD` | `transit_recorder` |
| Raw snapshot archive folder | `RECORDING_ARCHIVE_DIR` | `data/raw` (inside `backend/`, git-ignored) |
| Password of the read-only `grafana_reader` DB user | `GRAFANA_DB_PASSWORD` | `grafana_reader` (set the same value for docker compose) |

### API

- `POST /api/auth/login` `{username, password}` → `{token, username, role}`; `GET /api/auth/me`
- CRUD (`GET` list paged with `?page=&size=&sort=`, `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`) on
  `/api/vehicles`, `/api/users`, `/api/roles`, and the timetable (GTFS) tables
  `/api/agencies`, `/api/routes`, `/api/stops`, `/api/trips`, `/api/stop-times`, `/api/calendar-dates`,
  `/api/shape-points`. Timetable lists take filters as query parameters, e.g. `/api/trips?routeId=17&direction=1`,
  `/api/stop-times?stopId=260&sort=departureTime&sort=id`, `/api/stops?q=dworzec` (Swagger lists them all).
- Position history (recorded time series, city operator BKM's lines only; regional operators such as WSCHODEXPRESS
  and TUROSN are left out), each with a window `from`/`to` (ISO-8601, `to` exclusive, at most a year; see
  *Time windows* below) and an optional `route` (line):
  - `GET /api/positions/fleet` — per bucket of polls: vehicles running (average per poll), the most at once,
    reports and mean delay. Buckets are 1 minute (one poll) for windows up to 24 h and coarser beyond, keeping
    ≤ 1,500 points. The totals separate *running now* (latest poll) and *most at once* (peak poll) from *different
    vehicles seen*. The last is always larger, because vehicles start and end their runs during the window.
  - `GET /api/positions/delays` — vehicles and reports per minute of delay: under 1 min (incl. early), 1–2, …,
    9–10, 10 min or more. A vehicle counts in every band it was in.
  - `GET /api/positions/routes` — lines recorded in the window.
  - `GET /api/positions/replay` — animation frames (latest position of every vehicle per step; 1 min for short
    windows, coarser for longer ones up to 6 h, at most 360 frames, windows up to 92 days) as compact rows `[vehicle, lat, lon, delay, line, brigade]`.
    Repeat `route` for several lines. Responses are gzipped.
  - `GET /api/positions` — the raw position reports, paged (`page`, `size` ≤ 500; also `vehicle`), sorted by
    `sort=time|vehicle|line|duty|towards|delay|nextStop,asc|desc` (default `time,desc`). `excludeOutliers=true`
    leaves out reports more than 30 min late; those are outliers, most likely feed errors. The page highlights
    them in red and has a checkbox to hide them.
- `GET /api/lines/stats` — per line, from the regular weekday timetable: length (main route, km), shortest and
  longest end-to-end travel time, average speed, interval, regularity (coefficient of variation of the gaps between
  departures, graded A–F as in TCQSM) and vehicles in peak (06:30–08:30, 15:00–17:00) and off-peak
  (08:30–15:00, 17:00–20:00) hours, the same periods as on every timetable page. The definitions are on the Line statistics page.
- **Time windows.** Every report over recorded positions (position history, Fleet, hotspots, delay generation,
  abnormal trips) takes `from` and `to` (ISO-8601 instants, `to` exclusive). Both are optional: `to` defaults to now,
  and without `from` the window starts at the first recorded position (*all time*). `from` after `to` is a 400.
  Answers for the same window are kept for a minute or two; service days that are over are computed once and kept
  for 92 days.
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
  another operator's feed from `app.gtfs.extra` (`pksnova-500`: PKS Nova line 500); see below.
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
- `GET /api/bus-lanes` — the city's bus lanes from OpenStreetMap (`map/bus-lanes.json`, or the refreshed copy in
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
  short-turned (the bus, reporting all along, joined midway or left before the end), no signal (it stopped
  reporting partway but was back for its brigade's next trip), lost (it stopped reporting midway and didn't come
  back within 15 min), missed (not run while the brigade ran other trips; with where the brigade's bus was instead)
  and cancelled (no bus on the brigade all day). Trips during gaps in recording (3+ minutes without any report)
  aren't judged; trips are judged as soon as it's clear what the bus did. Counts overall and per line, and the
  abnormal trips (the latest 2000) with brigade, buses, stops seen, delays and a note. Each finished service day is
  judged once; the running day again after a minute. The last month is judged at startup in the background.
- `GET /api/brigades?date=&agency=` — the brigades of an agency (default: the city's, BKM) on a service day (default:
  today's): each with its lines, trip count and first and last times, plus the agencies whose timetables name brigades.
- `GET /api/brigades/{brigade}/day?date=` — a brigade's day: each trip as scheduled with what actually ran (a part per
  bus, from the first to the last stop it was seen heading to, timed by the reported delays), the parts not run (lost,
  missed, cancelled, turned short, skipped at a bus change) or not seen (the bus stopped reporting), its outcome as on
  Abnormal trips, and a summary (trips run in full, left on time between 1 min early and 3 min late, median start
  delay, buses).
- `GET /api/brigades/{brigade}/schedule` — a brigade's runs on the service day (03:00–03:00 Warsaw time) that `at`
  falls in (ISO instant, default now) from the timetable: line, headsign, first and last stop, scheduled departure
  and arrival. Brigades are named as on the live feed (`103-05`); the timetable's is the trip id's prefix. Used by
  the popups of the live map and the Delays replay.
- `GET /api/boundaries` — Białystok's city limits and city centre (Osiedle Centrum) as closed rings of
  `[lat, lon]`, kept as constants in `domain/CityBoundaries`. Taken from OpenStreetMap (relations 2910925 and
  4091287, via the Overpass API, 18 Nov 2026; © OpenStreetMap contributors, ODbL). Drawn on the live and Delays maps.

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
timetable, its ids (agency, route, stop, trip, service, shape) prefixed, so they never clash with the city's and an
import of one feed replaces only that feed's rows (the city's import leaves them alone too). Imported with
`POST /api/gtfs/<key>/import` and again every night at 04:50 (`extra-refresh-cron`), as these feeds cover about two
weeks at a time.

- `pksnova-500`: **PKS Nova line 500** (Białystok D.A. ⇄ Supraśl), the unofficial feed generated from Time4Bus
  (`https://files.girlc.at/gtfs/pks_nova_suprasl.zip`, listed on Transitland as `f-u92~pks~nova~suprasl`, CC0).
  Agency "PKS NOVA S.A.", route `pksnova:500`, a suburban line (from its number), about 50 trips a weekday.
- On the Routes page such a line shows like the city's (map, variants, termini, bus lanes, zone 2, travel times),
  run by its own operator. What the feed doesn't have is said rather than guessed: without brigades there are no
  brigades or depot runs; trips without a direction get one from where they start; stops without a fare zone are
  zone 1 inside the city limits and 2 outside. Its timetable is the weekday the line has the most trips (its feed has
  a service per date). It isn't on the live feed (zbiorkom.live), so nothing is recorded for it.

### Vehicle position history (time series)

Every 10 seconds from 03:30 to 01:30 (Warsaw time), **transit-recorder** polls the zbiorkom.live Białystok feed and
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
  upgrade between 01:30 and 03:30, when nothing is recorded.
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

## Frontend

- `core/auth` — `AuthService`, `authInterceptor` (Bearer token, logout on 401), `authGuard`, `roleGuard`, `*appHasRole`
- `core/api` — generic `CrudApiService` + one service per backend resource
- `layout/shell` — sidebar with every page + top-right hamburger menu (Settings, Logout)
- `store` — NgRx slices with functional effects: `auth`, `livePositions`, `routes` (all routes), and
  `stops`, `trips`, `stopTimes`, which hold one server-side page each (shared helpers in `store/paged.ts`);
  `gtfsImport` tracks the import. Only `auth` is registered at startup; every other slice is registered by its
  page's lazy-loaded route (`*.routes.ts`), so it loads with the page.
- `shared` — pieces several pages use: `app-time-window` (the time window and operators of every report page, see
  below), the time-series, hour-of-day and bar charts, the timeline styles, `createBaseMap` (Leaflet + OSM),
  `hostWidth` (chart width), `injectSetQuery` (merge changes into the URL's query parameters), time and delay
  formatting, and the colour palette (`cssColor` reads theme tokens).
- Time window: every page over recorded positions (Fleet, Position history, Delays, Stop hotspots, Delay
  generation, Abnormal trips) has the same control: from/to pickers in the first row, then buttons for the last
  1 min, 5 min, 30 min, 1 h, 2 h, 12 h, 24 h, 3 days, 1 week, 1 month and all time, a dropdown of checkboxes for the
  operators where the page can filter by them (Fleet), and the page's own filters. It is kept in the URL
  (`?range=1w`, or `?from=…&to=…` in local time, and `?agency=`).
- Startup bundle: telemetry (the OpenTelemetry SDK) loads as its own chunk right after startup, and the NgRx
  devtools are only in development builds (`store-devtools.prod.ts` replaces them in production).
- `pages` — login, home, live map (Białystok, Leaflet/OSM; dispatches a
  `livePositions` load every 5 s and draws markers from the store; filled by delay as on the Delays map, outlined by
  kind of line: urban blue, suburban black, regional magenta; filters by operator and delay in the URL, with a reset;
  the popup shows the brigade and its remaining runs today; the city limits and centre are drawn under the buses),
  settings (GTFS import for admins)
- Position history: vehicles running and mean delay every 10 s (each poll) for windows up to an hour, else every
  minute or coarser (SVG line charts with a hover/keyboard tooltip and
  a table view), running now / most at once / different vehicles seen, the delay distribution, and the raw reports, filtered by time window and line, all kept in the URL.
  Preset ranges refresh with each poll (up to 1 h) or every minute. Store slice `positionHistory`, registered by its lazy route.
- Delays: an animated replay of recorded positions on a map, each bus coloured by delay (green on time → yellow →
  orange → red at 10+ min). Filter by one, several or all lines and by time window. Play/pause, speed, loop and a timeline are at the top; the filters are kept in the URL.
  Clicking a bus shows its brigade and that brigade's plan for the rest of the replayed day from the replay's
  moment (the run under way in bold); the popup follows the replay. City limits and centre are drawn as on the live map.
- Line statistics: the figures above for every line in a sortable table (weekday timetable, planned values).
- Routes: pick a line from a table of kinds of line (rows: urban, suburban, night, regional) by geometry (columns:
  radial, diameter, arcantial, tangential, feeder, and not classified for other operators' lines); a line's button
  shows its capacity class by its border's thickness and its fill (strongest for main lines, dashed for irregular
  ones), with a legend. The selected line is kept in the URL as `?line=`; its title says what its geometry and
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
- Fleet: tiles with the buses in use, the fleet by model, by size class (smallest first) and by make (the model
  name's first word) — bars of in use / in the fleet, the 10 most and least used
  buses and the buses that broke down (when, and how many days until they were back, or that they aren't yet); a
  bar chart of every bus's hours, the most used first; and a search by number or model. A chosen bus (`?vehicle=`)
  shows its runs and breaks as a timeline (a row per day, or per run for a window of a day or less), its hours per
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
  band) on the Live, Delays and Routes maps, and Routes says how much of the line runs on them each way.
- Delay generation: a map of every stretch between stops coloured by the delay buses gain on it (red) or recover
  (blue), both directions side by side, for the whole day or a part of it; the stretches creating and recovering
  the most delay altogether; and each line's gain per run. Click a line for, per direction, a table of its stretches
  in route order with the change by hour of the day (a heatmap with the numbers in the cells), the runs that gained
  the most (overall or on a chosen stretch) with a chart and table of how their delay built up stop by stop, and
  its brigades. Window, part of the day and line are kept in the URL (`?part=`, `?line=`). Store slice
  `delayGeneration`, registered by its lazy route.
- Brigades: pick a day, an agency (BKM by default) and a brigade (search by name or line; all in the URL). Tiles sum the
  day up; a timeline shows every scheduled trip in turn (consecutive trips alternate blue and magenta, labelled with
  time, line and destination) and, under each, what actually ran: a bar per bus labelled with the bus and how late it
  set off, a dashed line from the scheduled to the actual start, parts not run hatched red, parts the bus wasn't seen on
  hatched grey, markers for a bus change, a lost trip and a detour, and outlines for trips without data or still to
  come. Hover a trip for details; a table lists every trip with the same.
- Abnormal trips: the trips of any time window (preset windows up to 12 hours refresh every minute). Cards count the trips by outcome (click a kind to list only those), a table shows the
  lines with abnormal trips, and the trips table lists each abnormal trip with its kind, scheduled times, line and
  brigade, buses, the stops it was seen on, its delays and what happened (filter by line, or by bus). Bus changes show
  where the new bus came from and whether the old one was possibly broken down or reassigned, and how long a broken
  one stood and what it did next. Each breakdown is told step by step, with a time line, on the bus's page under
  Fleet. Each detour gets a map on a line of its own under its trip: the usual route around it in blue, the bus's
  path in orange with its reports as dots. Store slice
  `tripAnomalies`, registered by its lazy route.
- Timetable pages (read-only): Trips (filter by route, direction, service, headsign) → Stop times of a trip;
  Stops (search, zone) → departure board of a stop. Filters and the page number live in the URL, so every view can be
  linked and the back button works.

```bash
npm start        # dev server with API proxy
npm test         # Vitest unit tests
npm run test:coverage   # once, with coverage of all of src/app (report in coverage/frontend/index.html);
                        # fails below the thresholds in angular.json (test options)
npm run build
```

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
