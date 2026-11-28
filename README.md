<h1>Transit Analytics <sub><sup><sub><sup>for Bialystok</sup></sub></sup></sub></h1>

[![CI](https://github.com/twardzikfilip/transit-analytics/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/twardzikfilip/transit-analytics/actions/workflows/ci.yml) [![App version](https://img.shields.io/github/package-json/v/twardzikfilip/transit-analytics?filename=frontend%2Fpackage.json&label=app)](https://github.com/twardzikfilip/transit-analytics/releases) [![Coverage](https://img.shields.io/badge/coverage-backend%20%E2%89%A590%25%20%7C%20frontend%20%E2%89%A582%25-brightgreen)](.github/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)  [![Last commit](https://img.shields.io/github/last-commit/twardzikfilip/transit-analytics/main)](https://github.com/twardzikfilip/transit-analytics/commits/main)

![Java 17](https://img.shields.io/badge/Java-17-orange?logo=openjdk) ![Python 3.9+](https://img.shields.io/badge/Python-3.9%2B-blue?logo=python&logoColor=white) ![Node 24](https://img.shields.io/badge/Node-24-5fa04e?logo=nodedotjs&logoColor=white) ![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f?logo=springboot&logoColor=white) ![Angular 22](https://img.shields.io/badge/Angular-22-dd0031?logo=angular&logoColor=white) ![TimescaleDB on PostgreSQL 17](https://img.shields.io/badge/TimescaleDB-PostgreSQL%2017-4169e1?logo=postgresql&logoColor=white)

## 1. Overview
The aim of this project is to explore possibilities of an in-depth evaluation of urban public transport networks based on open data. While it is planned to generalize it for this purpose, currently the sole focus is the bus and rail network in and around the city of Białystok, Poland.

The main data source is the feed of live positions of all active vehicles from [zbiorkom.live](https://zbiorkom.live) stored and aggregated over longer span of time. This is combined with static GTFS data sets for transport companies operating in the city: [BKM](https://www.komunikacja.bialystok.pl/) - official GTFS, [PKS Nova](https://pksnova.pl/), [Wschód Express](https://wschodexpress.pl/) [PlusBus](https://plusbus.pl) - unofficial GTFS.



- **Live situation:** a live map of the buses and their delays, .
- **Service:** delay genaration and recovery time, trips cut short, dwell times, impact of bus lanes or of lack thereof, schedule regularity
- **Fleet:** vehicle utilization, .
- **Network:** route frequency, capacity;  busiest corridors, network coverage in proportion to population density, especially in the new suburban areas.
- **Areas of Improvement:** bottlenecks, unused capacities, suboptimal routes or schedules, missing links

## 2. Run
- **Main prerequisites**: Docker, Java 17 and Node 24. Python 3.9+ with numpy is only for rebuilding the map data (`tools/geo`).

```sh
docker compose up -d postgres otel-lgtm     # database (required), and Grafana on localhost:3000, (optional)
(cd backend && ./gradlew bootRun)           # API on localhost:8080; leave it running
backend/recorder/upgrade.sh                 # recording live feed from zbiorkom.live API to database
cd frontend && npm install && npm start     # the app on localhost:4200
```

Log in as `admin` / `admin` (for local use only), open **Settings** and import the Białystok timetable. The live map
works at once; pges fill up with content as positions are recorded.

## Test
- `./gradlew check` in `backend`
- `npx ng test --watch=false` in `frontend`.

## 3. Architecture

```mermaid
flowchart LR
  feed[Live bus positions] --> recorder[Recorder]
  recorder --> db[(TimescaleDB)]
  gtfs[Timetables] --> backend[Backend]
  db <--> backend
  backend --> frontend[Web app]
  db --> grafana[Grafana]
```

| Part | What it does | Where |
|---|---|---|
| Recorder | Stores positions every 10 s. Runs on its own, so updating the backend loses no data. | `backend/recorder` |
| Backend | Imports timetables, matches buses to trips, serves the API (Spring Boot) | `backend` |
| Web app | Maps, charts and tables (Angular, Leaflet) | `frontend` |
| Grafana | Dashboards for the recordings and the app itself | `grafana` |

One server runs it all with Docker Compose; GitHub Actions tests and releases it ([set-up](docs/server-setup.md)).

## 4. Data models

Solid lines are foreign keys. Dotted lines are links by value, without a foreign key.

```mermaid
erDiagram
  agency |o--o{ route : runs
  route ||--o{ trip : has
  trip ||--o{ stop_time : "calls at"
  stop ||--o{ stop_time : "is called at"
  stop |o..o{ stop : "groups (parent_station)"
  trip ||--o| trip_brigade : "is worked by"
  trip }o..o{ calendar_date : "runs on (service_id)"
  trip }o..o{ shape_point : "follows (shape_id)"
  assumed_timetable_day |o..o{ calendar_date : "marks as assumed (date)"
  vehicle ||--o{ position_sample : reports
  route |o..o{ position_sample : "is reported as (route_name)"
  user_role ||--o{ app_user : has

  agency {
    bigint id PK
    varchar feed UK "with gtfs_id"
    varchar gtfs_id UK "with feed"
    varchar name
    varchar url
    varchar timezone
  }
  route {
    bigint id PK
    varchar feed UK "with gtfs_id"
    varchar gtfs_id UK "with feed"
    bigint agency_id FK
    varchar short_name
    varchar long_name
    varchar color
    smallint route_type
    varchar subtype "urban, suburban, night_urban, regional"
    varchar geometry
    varchar capacity_class
    varchar operating_agency
  }
  trip {
    bigint id PK
    varchar feed UK "with gtfs_id"
    varchar gtfs_id UK "with feed"
    bigint route_id FK
    varchar service_id
    varchar shape_id
    varchar headsign
    smallint direction
    varchar block_id
  }
  stop_time {
    bigint id PK
    bigint trip_id FK, UK "with stop_sequence"
    integer stop_sequence UK "with trip_id"
    bigint stop_id FK
    varchar arrival_time
    varchar departure_time
  }
  stop {
    bigint id PK
    varchar feed UK "with gtfs_id"
    varchar gtfs_id UK "with feed"
    varchar code
    varchar name
    double latitude
    double longitude
    varchar parent_station
  }
  shape_point {
    bigint id PK
    varchar feed UK "with shape_id, sequence"
    varchar shape_id UK "with feed, sequence"
    integer sequence UK "with feed, shape_id"
    double latitude
    double longitude
  }
  calendar_date {
    bigint id PK
    varchar feed UK "with service_id, date"
    varchar service_id UK "with feed, date"
    date date UK "with feed, service_id"
    smallint exception_type
  }
  assumed_timetable_day {
    varchar feed PK
    date date PK
  }
  gtfs_feed_import {
    varchar feed PK
    varchar fingerprint
    timestamptz imported_at
    bigint downloaded_bytes
  }
  trip_brigade {
    bigint trip_id PK, FK
    varchar brigade
    integer days
  }
  vehicle {
    bigint id PK
    varchar vehicle_number UK
    varchar type
    varchar model
    integer capacity
    varchar agency
    varchar vehicle_class
    varchar operating_agency
  }
  position_sample {
    bigint vehicle_id FK, UK "with observed_at"
    timestamptz observed_at UK "with vehicle_id"
    timestamptz fetched_at
    text route_name
    text brigade
    text headsign
    double latitude
    double longitude
    smallint bearing
    integer delay_seconds
    text next_stop_name
    smallint next_stop_sequence
  }
  recorded_brigade_line {
    varchar agency PK
    varchar line PK
    varchar live_brigade PK
    varchar brigade
  }
  day_summary {
    varchar report PK
    date day PK
    varchar fingerprint
    timestamptz computed_at
    bigint positions
    bytea content
  }
  app_user {
    bigint id PK
    varchar username UK
    varchar password_hash
    bigint role_id FK
    timestamptz created_at
  }
  user_role {
    bigint id PK
    varchar name UK
  }
```

- **Timetable** (from GTFS): `agency`, `route`, `trip`, `stop_time`, `stop`, `shape_point`, `calendar_date`. Less used columns are left out. `feed` says which feed a row came from. `assumed_timetable_day` lists the days continued after a timetable ran out. `gtfs_feed_import` keeps each feed's last import, so the same feed isn't imported twice.
- **Recordings:** `vehicle` and `position_sample`, a TimescaleDB table split by time. The views `fleet_5min` and `route_delay_hourly` are computed from it.
- **Worked out by the backend:** `trip_brigade` and `recorded_brigade_line` (brigades learnt from recordings), and `day_summary` (stored results of finished days).

## 5. Data sources

| Data | Source |
|---|---|
| Live positions and delays | [zbiorkom.live](https://zbiorkom.live) |
| City buses timetable (GTFS) | [BKM Białystok](https://komunikacja.bialystok.pl) |
| PKS Nova 500, Wschód Express, Turośń Kościelna 700 timetables (GTFS) | unofficial feeds made from Time4Bus, [files.girlc.at](https://files.girlc.at/gtfs/) (CC0) |
| Polregio and PKP Intercity trains (GTFS) | PKP Polskie Linie Kolejowe, put into GTFS by [Mikołaj Kuranowski](https://mkuran.pl/gtfs/) |
| Map, borders, woods, bus lanes, railways | [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL) |
| Residents | city and gmina registers, 2021 census, a few from Wikipedia ([details](backend/src/main/resources/places/README.md)) |

The licence of this repository doesn't cover this data: each source keeps its own terms.

## 6. TODO
tbd
