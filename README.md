# Transit Analytics

Starter full-stack app: Angular 22 + NgRx frontend, Spring Boot 4 backend, PostgreSQL with Flyway migrations.

```
docker-compose.yml   PostgreSQL 17 + Grafana LGTM (telemetry) for local development
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

### API

- `POST /api/auth/login` `{username, password}` → `{token, username, role}`; `GET /api/auth/me`
- CRUD (`GET` list paged with `?page=&size=&sort=`, `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`) on
  `/api/vehicles`, `/api/vehicle-positions`, `/api/users`, `/api/roles`, and the timetable (GTFS) tables
  `/api/agencies`, `/api/routes`, `/api/stops`, `/api/trips`, `/api/stop-times`, `/api/calendar-dates`,
  `/api/shape-points`. Timetable lists take filters as query parameters, e.g. `/api/trips?routeId=17&direction=1`,
  `/api/stop-times?stopId=260&sort=departureTime&sort=id`, `/api/stops?q=dworzec` (Swagger lists them all).
- `POST /api/gtfs/bialystok/import` (admin) — downloads Białystok's GTFS timetable and loads it; see below.
- `GET /api/vehicle-positions/latest` — latest stored position per vehicle
- `GET /api/zbiorkom/bialystok/positions` — live Białystok vehicle positions from [zbiorkom.live](https://zbiorkom.live),
  used by the live map. Fetched on every call and not stored. Vehicles seen for the first time are added to the
  vehicle table (number, type, agency, model). The agency `default` is stored as `BKM`, and GTFS type codes become names
  (`3` → `BUS`). If zbiorkom.live fails or times out, the endpoint returns 502.

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
- Rows that point at a route, trip or stop that isn't in the feed are skipped and counted in the response. Files the
  app doesn't load (e.g. `feed_info.txt`) are listed as ignored.
- Code: `gtfs/GtfsImporter` (generic loader: files are streamed from the zip into temporary tables with PostgreSQL
  `COPY`, then merged with SQL) and `gtfs/GtfsBialystokService` (download + import for Białystok).

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
```

## Frontend

- `core/auth` — `AuthService`, `authInterceptor` (Bearer token, logout on 401), `authGuard`, `roleGuard`, `*appHasRole`
- `core/api` — generic `CrudApiService` + one service per backend resource
- `layout/shell` — sidebar (Home, Vehicles, Live map) + top-right hamburger menu (Settings, Logout)
- `store` — NgRx slices with functional effects: `auth`, `vehicles`, `livePositions`, `routes` (all routes), and
  `stops`, `trips`, `stopTimes`, which hold one server-side page each (shared helpers in `store/paged.ts`);
  `gtfsImport` tracks the import. The timetable slices and `gtfsImport` are registered by their lazy-loaded routes
  (`pages/timetable.routes.ts`, `pages/settings/settings.routes.ts`).
- `pages` — login, home, vehicles (edit actions for ADMIN only), live map (Białystok, Leaflet/OSM; dispatches a
  `livePositions` load every 5 s and draws markers from the store), settings (GTFS import for admins)
- Timetable pages (read-only): Routes → Trips (filter by route, direction, service, headsign) → Stop times of a trip;
  Stops (search, zone) → departure board of a stop. Filters and the page number live in the URL, so every view can be
  linked and the back button works.

```bash
npm start        # dev server with API proxy
npm test         # Vitest unit tests
npm run build
```

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
