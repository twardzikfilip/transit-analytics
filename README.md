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
docker compose up -d

# 2. Backend  → http://localhost:8080 (Flyway migrates on startup)
cd backend && ./gradlew bootRun

# 3. Frontend → http://localhost:4200 (proxies /api to :8080)
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
| Record vehicle positions | `RECORDING_ENABLED` | `true` |
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
  and TUROSN are left out), each with a required window `from`/`to` (ISO-8601, `to` exclusive, at
  most 31 days) and an optional `route` (line):
  - `GET /api/positions/fleet` — per bucket of polls: vehicles running (average per poll), the most at once,
    reports and mean delay. Buckets are 1 minute (one poll) for windows up to 24 h and coarser beyond, keeping
    ≤ 1,500 points. The totals separate *running now* (latest poll) and *most at once* (peak poll) from *different
    vehicles seen*. The last is always larger, because vehicles start and end their runs during the window.
  - `GET /api/positions/delays` — vehicles and reports per minute of delay: under 1 min (incl. early), 1–2, …,
    9–10, 10 min or more. A vehicle counts in every band it was in.
  - `GET /api/positions/routes` — lines recorded in the window.
  - `GET /api/positions/replay` — animation frames (latest position of every vehicle per step; 1 min for short
    windows, up to 30 min for a week, at most 360 frames) as compact rows `[vehicle, lat, lon, delay, line]`.
    Repeat `route` for several lines. Responses are gzipped.
  - `GET /api/positions` — the raw position reports, paged (`page`, `size` ≤ 500; also `vehicle`), sorted by
    `sort=time|vehicle|line|duty|towards|delay|nextStop,asc|desc` (default `time,desc`). `excludeOutliers=true`
    leaves out reports more than 30 min late; those are outliers, most likely feed errors. The page highlights
    them in red and has a checkbox to hide them.
- `GET /api/lines/stats` — per line, from the regular weekday timetable: length (main route, km), shortest and
  longest end-to-end travel time, average speed, interval, regularity (coefficient of variation of the gaps between
  departures, graded A–F as in TCQSM) and vehicles in peak (06:30–08:30, 15:00–17:00) and off-peak
  (08:30–15:00, 17:00–20:00) hours, the same periods as on every timetable page. The definitions are on the Line statistics page.
- Routes in detail (`{line}` is the route's GTFS id, e.g. `103`):
  - `GET /api/lines` — all lines in numeric order, with their subtype.
  - `GET /api/lines/{line}/detail` — from the weekday timetable: every variant (direction and shape, with its route
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
- `POST /api/gtfs/bialystok/import` (admin) — downloads Białystok's GTFS timetable and loads it; see below.
- `GET /api/zbiorkom/bialystok/positions` — live Białystok vehicle positions from [zbiorkom.live](https://zbiorkom.live),
  used by the live map. Fetched on every call and not stored. Vehicles seen for the first time are added to the
  vehicle table (number, type, agency, model). The agency `default` is stored as `BKM`, and GTFS type codes become names
  (`3` → `BUS`). Each position carries its line's `subtype` (the stored one, else from the name, for other operators'
  lines). If zbiorkom.live fails or times out, the endpoint returns 502.
- `GET /api/vehicle-stats?window=day|week|month` — Vehicles in detail, from recorded positions over the last 24 hours,
  7 days or 30 days: hours in service and lines served per vehicle (top and bottom 10), vehicles on several lines on
  one day, and vehicles that left their brigade while the timetable had it running (switched to another line, or
  disappeared), with the vehicle that took over. Service days that are over are read once and kept; the last month
  is read at startup in the background. Answers are cached for 2 minutes.
- `GET /api/brigades/{brigade}/schedule` — a brigade's runs on the current service day (03:00–03:00 Warsaw time) from
  the timetable: line, headsign, first and last stop, scheduled departure and arrival. Brigades are named as on the
  live feed (`103-05`); the timetable's is the trip id's prefix. Used by the live map's popups.

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
  (400–499, or set by hand). New routes get it from their name (`RouteSubtype.classify`, migrations V10 and V12); an
  import keeps the subtype of existing routes, so a manual change stays.
- Rows that point at a route, trip or stop that isn't in the feed are skipped and counted in the response. Files the
  app doesn't load (e.g. `feed_info.txt`) are listed as ignored.
- Code: `gtfs/GtfsImporter` (generic loader: files are streamed from the zip into temporary tables with PostgreSQL
  `COPY`, then merged with SQL) and `gtfs/GtfsBialystokService` (download + import for Białystok).

### Vehicle position history (time series)

Every minute from 03:30 to 01:30 (Warsaw time), the backend polls the zbiorkom.live Białystok feed and stores every
vehicle's position in `position_sample`, a TimescaleDB hypertable. It also keeps the raw response, gzipped, in the
archive (`backend/data/raw/zbiorkom-bialystok/YYYY/MM/DD/<UTC time>.json.gz`).

- **Why PostgreSQL + TimescaleDB.** Half a year is about 25 million rows (≈ 940k vehicle-minutes per week from the
  timetable; weekday peak 181 buses). That's small for PostgreSQL, and it keeps positions next to the GTFS and vehicle
  tables for joins. TimescaleDB adds weekly partitions, columnar compression after 7 days, self-updating aggregates,
  and statistics functions (`timescaledb_toolkit`).
- **Expected size for half a year at one poll per minute:** ~2 GB of gzipped archive (13 KiB per snapshot), ~6 GB in
  PostgreSQL uncompressed (measured ~230 bytes per row with indexes), ~0.7 GB once compressed (measured 8.6× on a real
  week of data). Polling every 20 seconds (the default) is about three times that: most vehicles send a new report
  every few seconds, so nearly every poll stores a new row per vehicle.
- **Polling:** every 20 seconds (`cron`), never closer than 18 s (`min-interval` guard). A report seen twice (same
  vehicle and feed timestamp) is stored once.
- **Vehicles running** at a poll are the vehicles that reported in the minute up to it, so a vehicle that skips a poll
  still counts, and the figure doesn't depend on the polling interval.
- **Aggregates (refresh in the background, include the newest rows):**
  - `fleet_5min`: vehicles in service and mean delay per 5 minutes.
  - `route_delay_hourly`: per route and hour, `delay_stats` (mean/stddev via `average()`, `stddev()`) and
    `delay_pct` (percentiles via `approx_percentile(0.9, …)`). Combine buckets with `rollup()`.
- **Grafana:** the **Transit Fleet** dashboard shows vehicles in service, delay over time, a route × hour delay
  matrix, the most delayed routes, outliers (> 3σ from the route's mean for that hour), and a map of the vehicles at
  the end of the selected time range. It reads the database as `grafana_reader`, which can't see users or passwords.
- Positions are only recorded while the backend runs. Turn recording off with `RECORDING_ENABLED=false`.
- Vehicles with recorded positions can't be deleted (the history references them).

**Backfill saved snapshots** (one API response per file, `.json` or `.json.gz`, any folder depth). The command
imports them into `position_sample` and the archive, refreshes the aggregates for that period, and exits. Running it
twice adds nothing.

```bash
cd backend
./gradlew bootRun --args='--app.recording.import-dir=/path/to/snapshots --server.port=0'
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
INTEGRATION_TESTS=true ./gradlew test           # also boots the full context against the DB
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
- `store` — NgRx slices with functional effects: `auth`, `vehicles`, `livePositions`, `routes` (all routes), and
  `stops`, `trips`, `stopTimes`, which hold one server-side page each (shared helpers in `store/paged.ts`);
  `gtfsImport` tracks the import. Only `auth` is registered at startup; every other slice is registered by its
  page's lazy-loaded route (`*.routes.ts`), so it loads with the page.
- `shared` — pieces several pages use: the time-series and hour-of-day charts, `createBaseMap` (Leaflet + OSM),
  `hostWidth` (chart width), time and delay formatting, and the colour palette (`cssColor` reads theme tokens).
- Startup bundle: telemetry (the OpenTelemetry SDK) loads as its own chunk right after startup, and the NgRx
  devtools are only in development builds (`store-devtools.prod.ts` replaces them in production).
- `pages` — login, home, vehicles (edit actions for ADMIN only), live map (Białystok, Leaflet/OSM; dispatches a
  `livePositions` load every 5 s and draws markers from the store; filled by delay as on the Delays map, outlined by
  kind of line: urban blue, suburban black, regional magenta; filters by operator and delay in the URL, with a reset;
  the popup shows the brigade and its remaining runs today), settings (GTFS import for admins)
- Position history: vehicles running and mean delay every 20 s (each poll) for windows up to an hour, else every
  minute or coarser (SVG line charts with a hover/keyboard tooltip and
  a table view), running now / most at once / different vehicles seen, the delay distribution, and the raw reports, filtered by time range (1 min – 30 days or a custom from/to) and line, all kept in the URL.
  Preset ranges refresh with each poll (up to 1 h) or every minute. Store slice `positionHistory`, registered by its lazy route.
- Delays: an animated replay of recorded positions on a map, each bus coloured by delay (green on time → yellow →
  orange → red at 10+ min). Filter by one, several or all lines and by time window (last 15 min / hour / 24 hours
  or custom). Play/pause, speed, loop and a timeline are at the top; the filters are kept in the URL.
- Line statistics: the figures above for every line in a sortable table (weekday timetable, planned values).
- Routes in detail: pick a line from labels grouped by subtype (kept in the URL as `?line=`). The map shows the base
  route thick and other variants thin and dashed in the same colour, with zone-2 stops filled. Below: length,
  scheduled travel time and speed, zone 2, the variants, charts of measured travel time, speed and delay over the
  day (each with a table view), speed between stops in peak hours with the slowest stretches numbered, and a
  timeline of the brigades with their breaks. Depot runs are yellow and labelled everywhere, the standard end stops
  and depot-run stops are listed under the title, and a Vehicles section lists the buses and brigade changes.
  Store slice `routeDetail`, registered by its lazy route.
- Important corridors: two maps of every stretch between stops by its buses in peak hours (one by line width, one
  by colour in equal classes of a sequential blue), then the corridors above in a sortable table (busiest first), with each corridor's stops in an
  expandable list and its lines linking to Routes in detail. Store slice `corridors`, registered by its lazy route.
- Vehicles in detail: pick the last 24 hours, 7 days or 30 days (kept in the URL as `?window=`). Top 10 most and
  least used vehicles (hours in service), top 10 on the most and fewest lines, vehicles that served several lines on
  one day (in the order served), and vehicles that left their brigade outside the schedule. Every table has the
  vehicle's number, model and operator. Store slice `vehicleStats`, registered by its lazy route.
- Timetable pages (read-only): Routes → Trips (filter by route, direction, service, headsign) → Stop times of a trip;
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
| Traces | every HTTP request, with JDBC query spans | page load, every fetch/XHR, route changes, login attempts (`auth.login`), uncaught errors (`ui.error`) |
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
