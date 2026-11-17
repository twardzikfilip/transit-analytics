# Network quality and performance analysis: what more to measure

## Context
The app already has two kinds of data:
- **The static GTFS timetable (BKM):** about 1,080 stops, 14,900 trips, 122,000 shape points and 90 service days.
- **Vehicle positions:** a time series every 20 s, about 1 M reports since 2026-11-01. Each report has the line,
  brigade, delay, next stop, bearing and coordinates.

The app measures a first set of figures from them:
- **Line statistics:** planned speed, interval, regularity A–F.
- **Routes in detail:** measured travel times and speeds by hour, delay per 15 min, peak stretch speeds, brigades.
- **Important corridors:** planned buses and regularity per stretch.
- **Position history:** fleet in service, delay distribution.
- **Vehicles in detail:** usage, and vehicles that left their brigade.

This plan lists what else can be measured. It is split between **how the network is designed** (the timetable) and
**how it is operated** (positions against the timetable). It covers the metrics, the anomalies and bottlenecks they
reveal, the foundations they need, and an order to build them in.

### Limits of the data (they decide what is possible)
- **No passenger data.** There are no ticket validations, passenger counters (APC) or door signals.
  - Ridership, load and crowding can't be measured.
  - Use **passenger proxies** instead: population in each stop's catchment, scheduled frequency, transfer stops.
- **Positions every 20 s.** A stop passage is accurate to about ±10 s. Dwell time at a stop can only be estimated,
  and only when a vehicle reports twice while standing.
- **The delay comes from the feed** (zbiorkom). The app's own passage times are independent of it, which lets us
  check it.
- **Only BKM has a timetable.** WSCHODEXPRESS and TUROSN can be measured for speed, coverage and usage, but not
  against a schedule.
- **About 2.5 weeks of history.** Weekly patterns work. Seasonal effects (school holidays, winter) need months.
  Keep recording.

## Foundations (everything else builds on these)

### F1. Position on the route (linear referencing)
- Project every position onto its trip's shape: **distance along the shape** and **distance off the shape**.
- This gives stretch speeds, stopped points away from stops, detours, and passages that don't depend on the feed's
  `next_stop`.
- Reuse the shapes and stop distances from `LineTimetable` and `RunTracker.kms`.

### F2. Trip matching and stop events (the core table)
For each service day, match each observed run to a **scheduled trip**: brigade + direction + nearest departure. Reuse
`RunTracker.splitRuns` / `matchRun`.

Store one **stop event** per trip and stop: `stop_event(service_day, trip_id, stop_sequence, stop_id, vehicle_id,
scheduled_time, observed_time, quality)`.
- Interpolate passages as `RunTracker.passTimes` already does.
- `quality` records interpolated, extrapolated, or a gap in the feed.

Computation:
- One finished service day at a time, after it settles, as `VehicleStatsService` caches days.
- Backfill once.
- A TimescaleDB hypertable, about 500 k rows a day, compressed.

Almost every operations metric below is then a simple SQL aggregate over `stop_event`.

### F3. Baselines
- A **free-flow** travel time per stretch: the 15th percentile of night and early-morning runs.
- A **typical** value per stretch for each time-of-week band: median and spread (MAD).

These are the references for congestion, anomalies and schedule realism.

## A. Operations: reliability and punctuality (from stop events)
| # | Metric | What it reveals |
|---|---|---|
| A1 | **On-time performance (OTP)** per line, stop, hour and day: share of departures between 1 min early and 3 min late (TCQSM-style; make the band configurable) | Unreliable lines and times; the main headline figure |
| A2 | **Early departures** (more than 1 min early), especially at timing points | Drivers running ahead, or schedules too loose. Worse for passengers than lateness: the bus is gone |
| A3 | **Origin punctuality**: delay at the first stop, against delay gained en route | Whether lateness starts at the terminal (late pull-out, layover too short) or in traffic |
| A4 | **Delay generation per stretch**: Δdelay between consecutive stops, averaged | A map of where delay is *created* and where it is *recovered*. The core bottleneck map |
| A5 | **Delay propagation along a brigade**: a late arrival leads to a late next departure | Layovers too short to absorb delay; delay carried across a whole duty |
| A6 | **Service delivered**: share of scheduled trips (and vehicle-km) actually run; missed trips; short-turned trips (run only partly) | Cancellations and lost runs. Also a check on feed coverage |
| A7 | **Span of service kept**: first and last trips run on time | Late starts, early finishes |
| A8 | **Layover adequacy**: actual layover at terminals, against scheduled and against a minimum (e.g. 3 min) | Brigades that structurally depart late; driver-break problems |

## B. Operations: headways and passenger wait (frequent lines and corridors)
| # | Metric | What it reveals |
|---|---|---|
| B1 | **Actual headways** per stop (line and corridor), and their CV against scheduled | The regularity passengers see, beside the planned A–F already shown |
| B2 | **Excess wait time (EWT)**: AWT − SWT, with AWT = Σh² / (2Σh), as used by London | Minutes of extra waiting caused by irregular service, per stop and hour. Best for lines every 10 min or less |
| B3 | **Bunching and gaps**: actual headway under 25% or over 200% of scheduled; where bunches *form* (first stop with bunching) | Bus pairs; places to hold buses or add priority |
| B4 | **Corridor coordination**: on shared stretches, the combined actual headways against the planned offset | Lines planned 10/10 apart that run 2/18 in reality |
| B5 | **Missed transfers**: planned connections at transfer stops (arrival, then departure within 2–10 min) that failed in reality | Broken connections, especially for night and suburban lines |

## C. Operations: running time and bottlenecks
| # | Metric | What it reveals |
|---|---|---|
| C1 | **Congestion index per stretch**: travel time ÷ free-flow, by time of day | Where traffic slows buses, and when |
| C2 | **Bus-hours lost per day per stretch**: (actual − free-flow) × buses | Stretches ranked for **bus lanes and signal priority**. Weighting by buses makes it more than just "slow" |
| C3 | **Travel-time reliability**: buffer index (p95 − median) / median, planning time index | Unpredictable stretches and lines, even ones that aren't slow on average |
| C4 | **Stop hotspots off the stops**: points where vehicles stand still away from any stop, clustered (DBSCAN) | Traffic lights, junctions, left turns, parking problems. A map of where buses wait |
| C5 | **Dwell estimate at stops**: time stationary within about 30 m of a stop | Busy stops (a demand proxy), boarding problems, stops used as time points |
| C6 | **Schedule realism per stretch and time band**: scheduled against observed (median, p85) | Schedules too tight (always late) or too loose (always early or waiting). Gives **proposed running times** per time band: the classic running-time analysis |
| C7 | **Day-type effects**: weekday against Saturday and Sunday, school days against holidays, weather (IMGW open data) | Whether timetable types fit reality; rain and snow penalties |

## D. Operations: anomalies and data quality
| # | Metric or detector | What it finds |
|---|---|---|
| D1 | **Off-route detection**: sustained distance from the shape over about 150 m | Diversions and roadworks. A diversion that recurs means the GTFS is out of date |
| D2 | **Disruption detection**: stretch speed far below its baseline (robust z-score) for several vehicles or lines at once | Accidents and blockages, live or afterwards. A daily "disruptions" list |
| D3 | **Stuck vehicles**: in service with a line set but stationary for over 10 min away from a terminal | Breakdowns |
| D4 | **Feed quality**: reporting gaps per vehicle, stale or repeated positions, GPS jumps (implied speed over 90 km/h), vehicles without a brigade, brigade/line mismatches, feed delay against our own measured delay | Whether the data can be trusted; also data-quality KPIs to send to the provider |
| D5 | **KPI anomalies over time**: STL decomposition of daily OTP, EWT and speed per line; flags on unusual days | "Line 10 got 3 min slower since Tuesday", roadworks, timetable changes |
| D6 | **Vehicle–line fit**: model mapped to capacity (Solaris 18 m articulated against 12 m and minibuses) against the line's peak frequency | Small buses on trunk lines, articulated buses on quiet ones. Capacity offered per corridor and hour |

## E. Network design (static GTFS, plus open data)
| # | Metric | What it reveals |
|---|---|---|
| E1 | **Stop spacing** per line: mean, minimum, maximum; pairs under 250 m or over 800 m | Candidates to merge stops (speed) or add stops (coverage) |
| E2 | **Route directness**: route length ÷ straight-line distance, per line and per stretch; loops and detours | Winding lines that cost riders time |
| E3 | **Coverage**: population within 300/500 m of a stop (GUS 1 km grid or address points), weighted by frequency | Underserved areas; share of residents with frequent service (every 10/15 min or better) |
| E4 | **Accessibility**: travel time from every stop (or grid cell) to key places (centre, hospitals, university, railway station), using the timetable and also observed times (RAPTOR, r5) | Isochrones, transfers needed, districts that are poorly connected, planned against realistic |
| E5 | **Span and gaps**: first and last trip, longest gap per line and stop, weekend-to-weekday ratio | Lines with no evening or weekend service, holes in the timetable |
| E6 | **Transfer design**: transfer stops (lines within 150 m), scheduled transfer waits, timed connections at hubs | Where transfers are bad by design (B5 checks them in reality) |
| E7 | **Duplication**: lines sharing most of their stops; uncoordinated parallel lines | Redundancy that could become one frequent line |
| E8 | **Fleet efficiency (brigades)**: revenue time ÷ duty time, depot-run (deadhead) share, layover share, peak-to-base vehicle ratio | Costly duties, a large peak fleet, idle time |
| E9 | **Timetable quality**: clockface intervals, both directions balanced, regular departures per period | Hard-to-remember or uneven timetables |
| E10 | **GTFS validation**: the MobilityData `gtfs-validator`, plus checks of our own (impossible scheduled speeds, zero travel times, stops far from their shape, same-named stops far apart, calendar gaps) | Errors in the published feed itself |

## Recommended order
1. **Foundations F1–F3**
   - A service-day job writes `stop_event` and the stretch baselines.
   - Backfill the recorded days.
   - Check against `RunTracker` results on Routes in detail, which use the same method.
2. **"Service quality" page** with A1, A2, A3, A6 and B2: per line, per stop, by hour, against the network average.
   This gives the most value for one step.
3. **"Bottlenecks" page**: maps of A4 (delay generation), C1/C2 (bus-hours lost) and C4 (off-stop hotspots), plus
   the C3 reliability table.
4. **Schedule realism (C6)**: proposed running times per stretch and time band; flags for tight and loose
   schedules; layover adequacy (A8, A5).
5. **Headways (B1, B3, B4, B5)**: bunching and missed transfers, on the corridors already computed.
6. **Anomalies (D1–D5)**: a daily "what went wrong" digest, disruptions and detours.
7. **Design (E1–E10)**:
   - E1, E2, E5, E8, E9 and E10 need only the GTFS.
   - E3 and E4 need population and POI data, and an accessibility engine.

## Technology
- **Keep the main path in Spring Boot and SQL on TimescaleDB**, as now:
  - `stop_event` and its baselines as hypertables, or continuous aggregates
  - pure Java classes for the metrics, the pattern of `RunTracker` and `StretchStats`
  - Angular pages with the existing charts and maps
- **For exploration and the spatial or statistical parts**, use Python notebooks reading the same database:
  - `pandas`/`geopandas`
  - `scikit-learn` (DBSCAN)
  - `statsmodels` (STL)
  - `r5py` or OpenTripPlanner (E4)

  Move a metric into the app once it is useful.
- **Open data:**
  - GUS population grid
  - OSM (POIs, road network)
  - IMGW weather
  - school calendar

## Verification
- `stop_event` against Routes in detail travel times for the same lines and days, which should be identical, and
  against the feed's `delay_seconds`.
- Spot-check OTP, bunching and missed trips by replaying the same hour on the Delays page.
- Unit tests for each metric on small synthetic runs, as for `VehicleIncidents`; integration tests on a seeded
  service day, as for `VehicleStatsIntegrationTest`.

## Open questions
- **Priority.** Which matters most first: punctuality/reliability (A, B), bottlenecks for bus priority (C), or
  network design (E)?
- **OTP band.** Use −1/+3 min, or a city or operator standard if there is one?
- **External data.** Bring in population (GUS) and OSM data for E3 and E4?
