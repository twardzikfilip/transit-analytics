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

### API

- `POST /api/auth/login` `{username, password}` → `{token, username, role}`; `GET /api/auth/me`
- CRUD (`GET` list paged with `?page=&size=&sort=`, `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`) on
  `/api/vehicles`, `/api/vehicle-positions`, `/api/routes`, `/api/stops`, `/api/trips`, `/api/users`, `/api/roles`
- `GET /api/vehicle-positions/latest` — latest position per vehicle (used by the live map)

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
- `store` — NgRx `auth` and `vehicles` (entity adapter) slices with functional effects
- `layout/shell` — sidebar (Home, Vehicles, Live map) + top-right hamburger menu (Settings, Logout)
- `pages` — login, home, vehicles (edit actions for ADMIN only), live map (Leaflet/OSM, polls every 5 s), settings

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
