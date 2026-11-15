# Transit Analytics

Starter full-stack app: Angular 22 + NgRx frontend, Spring Boot 4 backend, PostgreSQL with Flyway migrations.

```
docker-compose.yml   PostgreSQL 17 for local development
backend/             Spring Boot 4.1 (Gradle, Java 17): JWT auth + CRUD REST API
frontend/            Angular 22 (standalone, signals, NgRx, Leaflet)
```

## Prerequisites

- Java 17+
- Node 24 (pinned via [Volta](https://volta.sh) in `frontend/package.json`; Angular 22 needs Node ≥ 22.22)
- Docker or Podman for the database (or any PostgreSQL 13+ you point the backend at)

## Run it

```bash
# 1. Database
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
