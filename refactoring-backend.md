# Backend review: database, API and code structure

**Summary:** the data model is sound. The main problems are:
- About 45 of the 50 generic CRUD endpoints, the whole JPA layer behind them, and one write-only archive table go unused.
- A 70-file `service` package where database reads, calculations and caching are mixed in the same classes.
- The same few reads written again and again: trains, shapes, position pairs and service days.

Nothing has been changed. Everything below was measured locally (database, the backend running locally, and the frontend source).

## 1. Database

### Base data
| Group | Tables | Notes |
|---|---|---|
| Timetables (GTFS copy) | `agency`, `route`, `stop`, `trip`, `stop_time` (467k rows, 122 MB), `shape_point` (283k rows, 75 MB), `calendar_date` | Each import replaces one feed's rows. Every table except `stop_time` has a `feed` column, so a feed can be replaced on its own. That duplication is deliberate and fine. |
| Recorded positions | `position_sample` (TimescaleDB hypertable, compressed after 7 days) | Written by the recorder. |
| Live fleet | `vehicle` | |
| Learnt from recordings | `recorded_brigade_line`, `trip_brigade` | Brigade numbers for operators whose timetables don't name them. |
| Bookkeeping | `gtfs_feed_import`, `assumed_timetable_day`, `flyway_schema_history` | |
| Users | `app_user`, `user_role` | |

### Aggregated and cached data
| Table or view | What it holds | Who reads it |
|---|---|---|
| `fleet_5min` (continuous aggregate) | Number of vehicles and mean delay per 5 minutes | **Only Grafana** |
| `route_delay_hourly` (continuous aggregate) | Delay statistics per line and hour | `LineStatsService` and Grafana |
| `day_summary` (27 MB, gzipped JSON) | Finished service days of 5 reports. The biggest are hotspots (16 MB) and delay-generation (11 MB). | `DaySummaries` |
| `city_rail_proposal_variant` | The 8 proposal timetables | `CityRailProposal` |

### Redundant or unused
1. **`rail_train`, `rail_train_call`, `rail_train_day` are written but never read.** Only `RailArchive` touches them, and only to write. The migration says the purpose is to keep past days' train timetables, which is fine for the future, but no feature uses them today. Decide whether to keep them as an archive or drop them.
2. **Columns that only reach unused endpoints:**
   - `agency`: `url`, `phone`, `email`, `fare_url`, `lang`, `timezone` reach only `/api/agencies`.
   - `trip`: `block_id` (also read by `ServiceDay`), `wheelchair_accessible`, `bikes_allowed` reach only `/api/trips`.
   - `app_user.created_at` is never read.

   These are cheap GTFS copy columns. Dropping them only makes sense together with the CRUD layer (section 2).
3. **`fleet_5min` exists only for one Grafana panel.** Keep it if that panel matters.
4. Nothing is actually stored twice. `position_sample.route_name` is free text rather than a key to `route`, but that's right because live data has lines the timetable doesn't.

## 2. API

### Unused endpoints
- **10 generic CRUD controllers expose 50 endpoints**, covering agencies, calendar dates, routes, stops, stop times, shape points, trips, vehicles, users and roles.
- **The frontend uses only 4 of them:**
  - the `GET` list endpoints for `/api/routes`, `/api/stops` and `/api/stop-times`;
  - `GET /api/vehicles/{id}`, which it uses only to look up a vehicle's number.
- **Every POST, PUT and DELETE is unused.** Editing imported timetable rows makes no sense anyway, because the next import overwrites them.
- **`/api/agencies`, `/api/calendar-dates`, `/api/shape-points`, `/api/trips`, `/api/users` and `/api/roles` are entirely unused.**
- `POST /api/bus-lanes/refresh` and `DELETE /api/admin/day-summaries` are admin endpoints documented in README-OLD. They're operator tools, so keep them.

### Endpoints that send more than the frontend uses
| Endpoint | Size | What's extra |
|---|---|---|
| `/api/network-coverage` | **3.5 MB** | 92% is the belt's woods and open land as polygons. It's static data rebuilt on every request; it could be simplified and cached, or served as a static file. |
| `/api/trip-anomalies` | **2.1 MB** | Each trip's detour route and notes are sent for every trip, but shown only for the one opened. `changeAt` and `note.params.toIndex` are never read. |
| `/api/corridors` | 745 KB | 73% is segment points. Rounding coordinates and simplifying the lines would roughly halve it. |
| `/api/city-rail/proposal` | 611 KB | All 8 timetables are sent, but the page shows one. `periodMinutes` and `computedAt` are unused. |
| `/api/trip-runs` | 373 KB | Every trip in the list carries its `stops` and `samples` (91%), which are needed only when a row is expanded. |
| `/api/lines/stats` | 19 KB | `validDays`, `firstDate`, `lastDate`, `shortestTravelSeconds`, `longestTravelSeconds` and `offPeakVehicles` are unused. |
| `/api/bus-lanes/report` | 34 KB | `elsewhere.offPeakDelayPerKm` and `hours[].otherKm` are unused. |
| `/api/zbiorkom/bialystok/positions` | 97 KB | `routeColor` is unused. |

The unused-field check matches field names against the frontend source, so it can miss fields but won't flag one that is used.

### Duplicated functionality
- **Three ways to list lines:**
  - `/api/lines`, which takes 17 s on a cold start because it walks `position_sample`;
  - `/api/routes?size=1000` (CRUD);
  - `/api/routes/subtypes`.
- **Two per-brigade day endpoints:** `/api/brigades/{b}/day` (`BrigadeDayService`) and `/api/trip-runs/day?brigade=` (`TripRunService`). Both are used, by different pages, and both build one brigade's day from the same trips and reports.
- **Slow on a cold start** (local, measured once):
  - `/api/bus-lanes/report` 33 s;
  - `/api/positions/fleet` 21 s;
  - `/api/delay-generation` 17 s;
  - `/api/hotspots` 12 s.

## 3. Code structure

### Size
- **Production code:** about 22,800 lines in 237 files. `service` alone has **70 files and 12,600 lines**, mixing every feature: trips, fleet, lines, rail, hotspots, bus lanes and coverage.
- **Biggest classes:**

  | Class | Lines |
  |---|---|
  | `TripAnomalies` | 880 |
  | `CityRailProposal` | 880 |
  | `TripAnomalyService` | 523 |
  | `TripRunService` | 487 |
  | `FleetService` | 444 |
  | `LineTimetable` | 443 |
  | `LineDetailService` | 433 |

### Separation of concerns
1. **`CityRailProposal` (880 lines) does six jobs:**
   - reads the network file;
   - loads trains and shapes with SQL;
   - matches stations to tracks;
   - models run times;
   - builds the timetable columns;
   - stores the variants and checks the network hash.
2. **`TripAnomalies` (880 lines) is one long rule engine.** About 300 lines of it turn results into sentences (`Phrase`, `heading`, `standNote`), which is text formatting, not judging.
3. **`TripAnomalyService` is really the shared reader of trips and reports.** `TripRunService`, `FleetService`, `BrigadeDayService`, `FaultyTrackers` and `RecordedBrigades` all depend on it just to call `trips(day)` and `reports(from, to)`. So the "anomalies" feature is a dependency of five other features.
4. **`LineDetailService` has 10 dependencies:** JdbcTemplate, `RouteRepository`, `LineTimetable`, `LineSubtypes`, `Clock`, three property classes, `DepotRouter` and `BusLanes`. That's a sign it does several pages' work.

### Dependency inversion
- **Every service gets `JdbcTemplate` directly and writes its own SQL.** No service depends on an interface such as "trips of a day" or "reports in a window". Each depends on concrete classes and on the table layout.
- **Two data-access styles side by side:**
  - JPA (entities in `domain/`, `CrudService`, 10 repositories) serves almost only the unused CRUD endpoints, plus user login;
  - everything that matters uses JdbcTemplate.

### The same read written several times
| Read | Written separately in |
|---|---|
| Rail trains with their calls | `CityRailService.trains()`, `RailBusComparison` and `CityRailProposal.trains()`, each with its own `Train`/`Call` records |
| `shape_point` reads | 7 classes |
| "Which trips run on a day" | `ServiceDay`/`ServiceDayCache` is the shared version, yet `CityRailProposal`, `RailBusComparison`, `LineTimetable`, `CityRailService` and `WeekdayTimetable` write their own `calendar_date` joins |
| Distance in metres | `Geo.meters` exists, yet `StopPlaces.meters`, `RecordedRoutes.meters` and `CityRailProposal.meters` re-implement it |

### Half SQL, half Java
Yes, and in two different styles for the same thing: pairs of consecutive position reports.

**Pairs and stops worked out in SQL with window functions, the rest in Java:**
- **Hotspots:** SQL finds standstills; `StandstillHotspots.java` clusters them.
- **Bus lanes:** SQL builds the pairs; Java matches them to lanes and sums them.
- **Delay generation:** SQL uses `lag()` per vehicle; Java works out the stretches.
- **Fleet:** SQL measures spans with `lead()`; Java totals them per vehicle.

**Pairs worked out entirely in Java:** `TripRunService.segments()` and `TripAnomalies` read raw reports and pair them in Java.

So one idea, "a bus's movement between two reports", has four SQL versions and two Java versions. Each SQL version filters gaps and line changes slightly differently, so their figures can quietly disagree.

## 4. Proposed solutions, in order of value for effort

1. **Remove the unused CRUD layer and JPA.**
   - Replace it with three small read endpoints the frontend actually needs: routes, stops and stop times. Answer the vehicle-number lookup from `/api/fleet`.
   - Keep `UserRepository` as a JdbcTemplate query for login.
   - This deletes `CrudService`, `CrudController`, about 20 service/controller pairs, `domain/` entities, `Specs` and the JPA starter. Roughly −1,500 lines, and one data-access style left.
2. **Shared readers behind small interfaces**, in a `data` (or `read`) package:
   - **`Timetables`:** trips of a day, with stops and shapes.
   - **`Reports`:** positions in a window, as consecutive pairs with one agreed rule for gaps and line changes.
   - **`RailTrains`:** trains of a day with their calls.
   - **`Shapes`.**

   Features depend on these interfaces instead of `JdbcTemplate` and `TripAnomalyService`. This removes the triple rail loader, the seven shape readers and the competing pair definitions.
3. **Pick one side for each calculation.**
   - SQL does selection and the per-vehicle pairs, in the shared `Reports` reader.
   - Java does everything after that.
   - Hotspots, bus lanes, delay generation and fleet then all start from the same pairs.
4. **Split `service` by feature:** `trips`, `fleet`, `lines`, `rail`, `hotspots`, `buslanes`, `coverage`, `brigades`. Then split the two 880-line classes:
   - **`CityRailProposal`:** `ProposalNetwork` (file and hash), `RunTimeModel`, `ProposalTimetables` (columns and today's trains) and `ProposalStore` (the table).
   - **`TripAnomalies`:** keep the judging; move the text into `AnomalyNotes`.
5. **Trim the payloads:**
   - Serve the coverage belt once as a static, simplified, cacheable file (−3 MB).
   - Load trip-run `samples`/`stops` and anomaly detour/notes only when a row is opened (about −2.2 MB together).
   - Send one proposal timetable per request, or keep all 8 and just drop the unused fields.
   - Remove the unused fields listed in section 2.
6. **Merge the overlaps:**
   - one brigade-day endpoint;
   - one line list: `/api/lines`, read from the timetable plus a cheap distinct query instead of the 17 s walk.
7. **Decide on the rail archive:** keep it, documented as "kept for future analysis", or drop the three tables.

**My recommendation:** do 1, 5 and 6 first, as separate pull requests. They're contained, easy to verify with the existing tests, and remove the most code and bytes. 2 and 3 are the real cleanup but touch every report, so they should go one feature at a time with the figures compared before and after. 4 is mostly moving files and fits best alongside 2.

## 5. For later: `JdbcClient` instead of `JdbcTemplate`

Hibernate/JPA isn't worth bringing back: most queries are analytical (window functions over the `position_sample`
hypertable, `DISTINCT ON`, `array_agg`, arrays, jsonb, `COPY`) and read hundreds of thousands of rows into small
records. HQL can't express most of them, so they would stay native SQL, only slower (entity bookkeeping per row) and
with a second data-access style beside them. It would help only the few simple lookups and writes (users, vehicle
classes, brigades), which are already the simplest code.

What would make the JDBC code easier to read is Spring's **`JdbcClient`** (Spring 6.1+, already on the classpath
through `spring-boot-starter-jdbc`):

```java
jdbc.sql("SELECT gtfs_id, name, latitude, longitude FROM stop WHERE feed = :feed AND gtfs_id = ANY (:ids)")
    .param("feed", feed).param("ids", ids.toArray(String[]::new))
    .query(Station.class).list();
```

- Named parameters instead of counting `?` positions.
- Rows mapped to records by column name (`.query(Record.class)`), so most `rs.getString(...)` lambdas go.
- Same SQL, same speed, same transactions: it wraps the same `JdbcTemplate`.

**How:** adopt it gradually, one class at a time, starting with the `data` readers (`Timetables`, `Reports`,
`Shapes`, `RailTrains`), then the features as they are touched. Keep `JdbcTemplate` where a `RowCallbackHandler`
streams a large result (reading a day of positions) or for `batchUpdate`, unless `JdbcClient` does it as well there.
