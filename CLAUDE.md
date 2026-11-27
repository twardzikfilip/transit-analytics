# CLAUDE.md

Transit analytics for Białystok's buses (BKM and other operators): live positions, recorded history, timetable
(GTFS) analysis and network coverage. `README-OLD.md` is the full reference (API, pages, data, deployment); this file is
the short version for working on the code.

## Layout

- `backend/`: Spring Boot 4, Java 17 (no `List.getFirst()` and other Java 21 API), Gradle (Kotlin DSL), JdbcTemplate
  over PostgreSQL + TimescaleDB (`position_sample` hypertable), Flyway migrations in `src/main/resources/db/migration`.
  Packages under `com.transitanalytics`: `web` (controllers), `service`, `recording` (position history),
  `dto`, `gtfs` (import), `zbiorkom` (live feed), `geo`, `buslanes`, `classification`, `security`.
  - `backend/zbiorkom-feed/` (live feed client, shared) and `backend/recorder/` (the app that stores positions).
  - Settings in `src/main/resources/map/*.yml` and `classification/`; `./config/...` files override them.
  - Place data (borders, woods, Biała valley, residents) in `src/main/resources/places/` (see its README): generated
    files under `osm/`, hand-edited CSVs under `population/`.
- `frontend/`: Angular 22 (standalone components, signals), NgRx (`createFeature`, functional effects; each page
  registers its own slice in its `*.routes.ts`), Leaflet maps, Vitest through `ng test`.
  - `src/app/pages/<page>/`, `src/app/store/<slice>/`, `src/app/core/` (API, auth, theme, title, models),
    `src/app/shared/` (maps, charts, formatting, palette, icons, time window), `src/app/layout/shell/` (sidebar).
- `tools/geo/`: OpenStreetMap fetch (`fetch_osm.sh [file.json]`, Overpass, tries mirrors with backoff) and the
  Python builds of `places/osm/*.json` (`build_bialystok_geo.py`, `biala_valley.py`, `build_belt_geo.py` with `build_belt_land.py` and `worldcover.py`, `build_railways.py`, `build_city_rail_proposal.py`). Raw
  answers go to `tools/geo/raw/` (not committed). No shapely; numpy is available.
- `docker-compose.yml`: `postgres` (TimescaleDB), `recorder`, `otel-lgtm` (Grafana on :3000).

## Run and check

```sh
docker compose up -d postgres otel-lgtm
cd backend && ./gradlew bootRun                 # :8080
cd frontend && npx ng serve                     # :4200, proxies /api to :8080 (add --host 0.0.0.0 for the LAN)
```

Dev users: `guest`/`guest`, `admin`/`admin`.

**The recorder must keep running.** It stores the live positions all the time, and a gap is lost data. It runs in
Docker (`restart: unless-stopped`; Docker starts at boot), separately from any dev session. Never stop it; upgrade it
with `backend/recorder/upgrade.sh`, which leaves no gap. Check it with `curl -s localhost:8081/actuator/health`
(`recording.lastStored` should be seconds old). Never run a bare
`docker compose up` while it runs: that can recreate it (after a reboot, `docker start` the stopped containers). Its
folders `backend/data/raw` and `backend/data/spool` must belong to the user it runs as (1000), or polls
aren't archived (the health says so from recorder 1.0.0 on).

Before calling a change done:
- Backend: `cd backend && INTEGRATION_TESTS=true ./gradlew check` (needs the database up, and the `transit_test`
  database the tests use: README-OLD, "Tests"; coverage floor 90%).
  To run some tests: `./gradlew :test --tests '*Name*'` (`:test`, the root project; plain `test` also runs the
  subprojects, where the filter fails).
- Frontend: `cd frontend && npx ng test --watch=false`, `npx ng build`, `npx prettier --check src`.
  A run whose tests all pass can still exit 1 for unhandled errors: read the log, not only the counts.
- UI changes: look at the page in a browser (log in as guest), with no console errors. Styles in `angular.json`
  need the dev server restarted.

## Conventions

- **Errors:** backend 4xx/5xx answers show only as toasts (`core/api/error-toasts.ts`, ngx-toastr); never render an
  error's text on a page. `errors-as-toasts.spec.ts` opens every page with the API failing; add new pages there.
  ngx-toastr declares Angular 21, so `package.json` has an `overrides` entry.
- **Colours:** tokens at the top of `src/styles.scss` (Bootstrap greys, `--primary` #00a9bc, `$green_bkm`,
  `$lightblue_bkm`, one `--radius` of 8 px and one `--shadow`); colours set from code are in `shared/palette.ts`,
  kept in step with the tokens. Light theme by default, dark via `data-theme` (`core/theme/theme.ts`).
- **URL state:** filters, time windows and selections live in query parameters. The live map on Home has its own
  (`mapAgency`, `mapLineType`, `mapMinDelay`) so the history's time window never changes it.
- **Page titles** come from the route's `title` (shown in the header; `core/title/page-title.ts`); a title can be a
  function of the path ("Brigade 103-05") or a resolver ("Vehicle 230", `fleet.routes.ts`).
- **One thing's page** has its own route under its list (`/brigades/:brigade`, `/vehicles/:id`,
  `/routes/route/:line`); filters stay in query parameters. Its way back is the header's chevron
  (`injectBackLink`, `core/title/page-back.ts`); Routes still has its own back button.
- **Loading:** show `<app-skeleton>` (`shared/skeleton`, ngx-skeleton-loader) shaped like the content while it loads,
  and only while loading (a failure is a toast, not a skeleton). The header's progress bar comes on by itself for
  `/api` requests; mark polling with the `BACKGROUND` context (`core/api/activity.ts`).
- **Tests:** a page test renders with `renderPage(...)` and `stateAfter(feature, ...actions)` from
  `src/app/testing/page-testing.ts`; maps in tests need `useSvgMaps()`. Backend database tests use
  `@DbIntegrationTest` and `TimetableFixture`.
- **Texts:** every UI text is a key in `frontend/src/i18n/en.json`, `pl.json`, `de.json` and `fr.json` (all four, or
  `translations.spec.ts` fails), shown with `t('section.key', params)` or `tn()` for counts (`core/i18n/t.ts`;
  components expose `protected readonly t = t`; where a template already uses `t` as a loop variable, import it as
  `tr`). Long explanations with markup go through `[innerHTML]`. Use the `date`/`number` pipes from
  `shared/i18n/locale-pipes.ts`, not Angular's.
- **Versions:** the app's (`VERSION`) and the recorder's (`backend/recorder/VERSION`) are set with `tools/version.sh`
  (README-OLD, "Versions"); a change in `zbiorkom-feed` raises both.
- **Docs:** keep `README-OLD.md` in step with behaviour (API section, frontend pages section).
- Code comments and UI text: plain English, short sentences; say what something is and why, not a history of changes.

## Working with the user

- Commit only when asked; never push or open a PR without asking. Branch off rather than commit to `main`.
- Don't send the user's data to outside services. Overpass/OpenStreetMap fetches of public map data are fine.
- Ask before large or irreversible changes; for UI tweaks, just make them and show the result.
