# Page workload: backend time and data sent per page

Measured on 21 Nov 2026, around 11:45, on the development machine.
- **Setup:** backend `bootRun`, TimescaleDB in Docker, Angular dev server. One browser, each page on its default
  window, opened twice: first visit, then a repeat visit a few seconds later.
- **Database:** `position_sample` held 3.9 M reports (982 MB), recorded since 1 Nov. A full day is now about 1 M reports.
- **Time:** request sent to last byte, so it includes the dev proxy, a few ms.
- **Size:** the JSON as the page receives it. *gzip* is what goes over the wire; the backend compresses JSON over 2 KB.

## Per page

| Page | Calls (slowest first) | First visit | Repeat visit | JSON | gzip |
|---|---|---|---|---|---|
| Home (live map) | `zbiorkom/…/positions` every 5 s, `bus-lanes`, `boundaries` | 0.2 s | 0.1 s | 220 KB | 40 KB |
| Home (recorded positions, 24 h) | `positions/fleet`, `/delays`, `/routes`, every minute | **6.5 s** | **5.5 s** | 180 KB | 28 KB |
| Brigades | `brigades` | 0.3 s | 0.3 s | 17 KB | 2 KB |
| Fleet (1 week) | `fleet` | **5.9 s** | 0.02 s | 59 KB | 8 KB |
| Abnormal trips | `trip-anomalies` | 0.03 s (warm-up) | 0.02 s | 147 KB | 13 KB |
| Stop hotspots (1 month) | `hotspots` | **4.8 s** | **4.9 s** | 126 KB | 20 KB |
| Bus lanes | `bus-lanes/report`, `bus-lanes`, `boundaries` | **7.2 s** | 0.02 s | 157 KB | 34 KB |
| Delay generation | `delay-generation` | **6.3 s** | 0.06 s | 354 KB | 84 KB |
| Routes (line 103) | `lines/1/performance`, `lines/1/detail`, `bus-lanes`, `lines` | 2.0 s | 1.8 s | 365 KB | 56 KB |
| Line statistics | `lines/stats` | 0.3 s | 0.3 s | 16 KB | 2 KB |
| Important corridors | `corridors` | 0.1 s | 0.1 s | **745 KB** | 176 KB |
| Network coverage | `network-coverage` | 0.2 s | 0.2 s | **1.3 MB** | **327 KB** |
| Stops, Trips, Stop times | paged lists | < 0.1 s | < 0.1 s | 13–33 KB | 1–2 KB |

Timetable pages (Routes, Line statistics, Corridors, Network coverage, the lists) are already cheap to compute. The
cost is in the pages over recorded positions, and in the size of two static answers.

## Findings

### 1. The days at a window's edges are read again on every request (biggest win)

- **Measured:** the same 24 h window (two cut days) takes 2.1–2.6 s for `fleet` every time, and
  0.6–0.7 s each for `delays` and `routes`. A week takes 0.7–1.4 s for `fleet` (five whole days cached), and
  2.8 s for `delays`, which has no cache.
- **Cause:** `ServiceDayCache` keeps only whole service days that are over.
  - A preset window ends "now" and starts at an odd time, so it cuts two days.
  - Those days are queried again on every request, even when they are over.
- **Effect on Home:** the default 24 h window cuts both of its days, and it reloads every minute. While Home is open,
  the backend scans about 1–1.3 M reports a minute, per open tab.
- **Fix, no new tables:** cache every day that is over as a whole day, and cut it to the window in memory.
  `FleetPolls.between` already does this for polls. Only today would then be read from the database, and only since
  the last read (today's reports are appended, never changed). Expected: tens of ms.
- **The same fix helps:**
  - Stop hotspots: 4.5 s for every new window, 0.006 s for the same window again. Its presets move every minute,
    so the 2-minute report cache always misses.
  - Fleet, Bus lanes and Delay generation on their first visit, and every time their preset window moves.

### 2. Delay bands and lines on Home: aggregate in the database

- **`delays`:** has no per-day cache. A week takes 2.8 s.
- **Option A:** apply the per-day caching from finding 1.
- **Option B:** a TimescaleDB continuous aggregate, per hour, vehicle and band, e.g.
  `(bucket, vehicle_id, route_name, band, reports)`.
  - Size: about 150 vehicles × 4 bands × 24 hours, so under 15,000 rows a day.
  - Distinct vehicles per band over any window become cheap.
  - The window's partial hours at each edge are read raw.
- **`routes`:** the same aggregate gives the lines recorded in a window (`DISTINCT route_name`).
- **Existing aggregates:** the database already has two, `fleet_5min` and `route_delay_hourly` (migration V9).
  - Only Grafana uses them.
  - `fleet_5min` counts distinct vehicles per 5 minutes, which isn't the app's "vehicles running" (see `FleetPolls`).
  - So the app can't simply switch to them; a new aggregate fitted to these queries is needed.

### 3. Caches that miss for no good reason

- **Routes:** `lines/{id}/performance` takes 1.6–2.0 s on every visit to a line. Its data changes once a day, so a
  per-line cache until the next service day would make repeat visits instant.
- **Warm-up:** Fleet, Bus lanes and Delay generation take 6–7 s on the first visit after a start. Their default
  windows could be added to the startup warm-up, as Abnormal trips, Stop hotspots and position history are.

### 4. Payload

- **Network coverage (1.3 MB; 327 KB gzipped):**
  - Fixed until the next restart, so `Cache-Control`/`ETag` would let the browser keep it.
  - Rounding coordinates to 5 decimals (about 1 m) would cut it by about a third.
  - Simplifying the drawn woods polygons would cut it further.
- **Important corridors (745 KB; 176 KB gzipped):** fixed per timetable, so the same `ETag` applies.
- **Live map (96 KB every 5 s, 11 KB gzipped):**
  - More than half is the upcoming stops of every vehicle (44 KB without them). Only the popup shows them.
  - Fetching them when a popup opens would bring an open Home tab from about 8 MB an hour to about 4 MB.

## What I would do, in order

1. Per-day caching of whole days, cut to the window in memory (finding 1). It is one change in `ServiceDayCache` and
   its users, with no schema change, and it fixes Home, Stop hotspots, Fleet, Bus lanes and Delay generation.
2. A per-line, per-day cache for `lines/{id}/performance`, and the warm-up for the remaining pages.
3. `ETag` on the static answers (coverage, corridors, lines); trim the live map's upcoming stops.
4. Continuous aggregates only if the data grows past what per-day caching handles. A cold "all time" window reads
   every day once. At 1 M reports a day that takes a few seconds per month of data.
