# Abnormal trips: analysis and proposed rules

Analysis of the week 16–22 November 2026 (service days), made on 23 November 2026. No code was changed for it; the
recording box was enlarged afterwards (see the first section).

Of 25,467 scheduled trips, 23,315 were run in full, 966 were judged abnormal and 1,186 couldn't be judged ("not
recorded", during gaps in recording).

| Kind | Trips | Of which other operators |
|---|---:|---:|
| No signal | 676 | 242 |
| Cancelled | 147 | 139 |
| Missed | 28 | 9 |
| Lost | 29 | 11 |
| Short-turned | 33 | 8 |
| Detour | 32 | 30 |
| Bus changed | 21 | 0 |

**In short:** most abnormal trips aren't service problems but gaps in what was recorded, and the biggest single
cause was the recording area. The proposed categories are the right idea, but each needs a check that tells "the trip
didn't run" from "we didn't see it run".

## 1. The recording box cut lines off

The recorder asks zbiorkom.live only for the buses inside a bounding box, and 296 of the 1,635 stops of the
timetables were outside it: 273 of Wschód Express's, 14 of Turośń Kościelna's, and 12 of BKM's own (line 104 to
Juchnowiec).

- 486 of the 676 "no signal" trips were lines crossing the box's edge: 104 at Juchnowiec (just south of 53.016°),
  406 at Kobuzie and Borsukówka (north of 53.244°), 413 at Zabłudów (53.014°).
- Lines 404, 468, 469 and 470 run entirely outside the box (Trzcianne and Krypno, to the north-west): their 68
  "cancellations" were artifacts. They have no learnt brigades for the same reason.

**Box changed** from `22.921150,53.016270,23.382496,53.243687` to `22.45,52.88,23.48,53.44` (minLon, minLat, maxLon,
maxLat): every stop and route shape of all four timetables (extremes 22.478–23.454° E, 52.896–53.421° N) with 1.5–2
km to spare. Set in `backend/recorder/src/main/resources/application.yml`, `backend/src/main/resources/application.yml`
and the README.

- One request to zbiorkom.live with the new box (Monday 10:04) returned 161 buses against 160: one Wschód Express bus
  of line 406 just past the old northern edge. The amount of data stays about the same.
- Lines 404, 468 and 469 run only around 06:30–07:40 and 13:40–16:12; whether their buses are in the live feed at
  all can only be seen then.
- The running recorder uses the new box only once upgraded (`backend/recorder/upgrade.sh`). Days already recorded keep
  their gaps.

## 2. The proposed categories, checked against the data

### 2.1 A trip missing completely, the brigade's trips before and after recorded

Three different situations; only the first is "run but not recorded".

- **Bus silent, back on time where the missing trip ended → run, not recorded.** N1 and N5 on 22 November: both
  buses silent from before midnight, both back at 00:49 at the same moment, on time on their next trips.
- **Bus reporting, but stuck on its previous trip → really skipped to catch up.** Bus 395 (005-01) stood 38 minutes at
  Mieszka I/Apteka, its delay rising from 9 to 37 minutes. Meanwhile bus 396 ran line 5 with no brigade, possibly in
  its place.
- **A different bus continues the brigade → breakdown, trips not run.** 103-02: bus 038 went silent at 05:32, bus 040
  ran the brigade's later trips.

**Rule:** decide by where and when the bus reappears, and whether it is the same bus.

### 2.2 Parts of a trip missing, the rest suggesting it ran normally

These are the 190 "no signal" trips inside the box (the other 486 were the box).

- The bus was silent for a median of 6 minutes (quartiles 3 and 11), missed a median of 3 stops, and always came back
  on its brigade's next trip.
- A third come from two buses with faulty trackers: 046 (34 trips) and 212 (30), across many lines, places and days.

**Rule:** run, with a gap in its data, when:
- the bus reappears on the brigade's next trip, at or near its start;
- the silent time is at least the scheduled time of the stops missed;
- its delay changed by less than about 5 minutes.

Without the position check this would hide short turns made while the bus was silent.

### 2.3 Several trips missing for one brigade

The idea that an operator wouldn't leave a whole brigade unrun holds only partly.

- **Gap filled by another bus → real service gap.** 011-04 missed 2 trips and 014-02 three until a replacement came.
  Bus 402 (011-04) reported a delay of 58 minutes at 09:00, then went silent at 09:06; bus 024 took over later.
- **Lost, then nothing for the rest of the day → can't be told from a tracker failing.** 006-02 (lost at 15:49, then
  4 trips missed), 006-01, 107-01.
- **No bus on the brigade at all, but buses ran with no brigade.** Wschód Express buses 1097 and 1098 ran lines
  414/416 every evening from about 20:40 for over 3 hours with an empty brigade: about 4 false "cancelled" trips a
  day. BKM's 101-03 on 16 November (8 trips with no bus) isn't explained yet.

**Rule:**
- the same bus comes back where the missing trips would have left it → not recorded;
- another bus takes over → breakdown gap;
- a bus with no brigade ran the line at those times → run, brigade unknown;
- nothing comes back → unknown, not "cancelled".

### 2.4 Abnormal delay, or standing still

43 times that week a bus on a brigade stood in one place for 10+ minutes while its delay grew by about a minute per
minute. The current rules have no category for it.

- **Real incidents (about 20).** A breakdown becomes "bus changed" or "lost"; otherwise a 15–77 minute stop is judged
  "completed". Examples: Poleska, Gen. Andersa, Radzymińska, Nowowarszawska (002-02, 77 minutes).
- **False alarms:**
  - layovers at terminus loops ("…/Pętla"), where the feed lets the delay grow while the bus waits;
  - the same stop at the same time every day: 108-01 at al. Piłsudskiego/Kościelna at 05:19, a planned pause the
    feed counts as delay.
- **Two buses stuck at the same place at once is a traffic jam,** not a breakdown: 100-02 and 100-04 at
  Wysockiego/Outlet, 21 November 18:16.
- **Delays over about 20–30 minutes stop reports matching their trip,** so the next trips look "missed" when the bus
  is just very late. Bus 388 (024-04) stood at Dojlidy Fabryczne and then Nowowarszawska, its delay reaching 56
  minutes; its last trip was judged missed.

**Rule:** standing at least 10 minutes within 50 m, delay rising by at least 0.7 s per second, more than 150 m from
the trip's ends, and not repeating at that stop and time on other days → stalled. Several buses stalled at one place
at once → traffic.

### 2.5 Recordings with no brigade

0.16% of BKM's reports (6,817 of 4.27 million), 2.2% of Wschód Express's. Most are short blips that are already
filled in from the bus's neighbouring reports. The long ones matter:

- **Recurring BKM runs at the same times every day** with an empty brigade, to check against the timetable: line 18 at
  about 08:20 and 23:00 (40–51 minutes, headsign Gen. Sosabowskiego/Kapralska NŻ), line 25 at about 22:35 and 23:00,
  line 13 at about 22:32.
- **The Wschód Express evening runs** of 2.3, judged as cancellations.

**Rule:** a run with no brigade that fits a trip by line, time and headsign is that trip, run with no brigade; one
that fits nothing is an unscheduled run.

### 2.6 Short turns

The proposed rule (no time gap, turning back the other way, smaller delay afterwards) is right. The current rule is
too broad: only a handful of its 33 short turns pass it.

- **About 10 are the feed switching to the next trip early:** the bus was last seen 2–3 stops before the end (37/40,
  25/27, 29/31) and reappeared at once on the brigade's next trip with the delay reset to 0. Line 18 at 42. Pułku
  Piechoty does it repeatedly.
- **4 are line 700's 17:18 trip at Borowskie Olki,** whose brigade (700-T2) was learnt wrongly from one late bus.
- **Others** are at the box edge (line 104) or a trip to the depot (006-02 at Hurtowa).
- **Real ones** look like 014-02: the delay dropped from 28 to 6.6 minutes after turning.

**Rule:** left at least 3 stops before the end, no gap, delay at least 3 minutes smaller afterwards, not a trip to the
depot. Short turns hidden in a recording gap would need the position check of 2.2.

### 2.7 Bus changed

Detecting a change is sound: all 21 are real changes. Calling all 21 "broken down" isn't.

| What the first bus did | Trips | What it means |
|---|---:|---|
| Stood 11–33 minutes, delay growing, then went silent | 7 | Breakdown |
| Drove off at once, 0.9–4.6 km | 8 | Sent back drivable, or a planned swap |
| Went silent at once | 6 | Can't be told from its tracker being switched off |

## 3. Other cases the categories should cover

- **Trips to the depot listed as passenger trips.** Short last trips to Hurtowa or Popiełuszki/Słoneczna (006-01
  23:35–23:37, 012-05 23:01–23:07, 107-01 21:40–21:51) are judged "missed". The Routes page already knows the depot
  runs, so these could be left out.
- **Faulty trackers.** A bus that often drops out (046, 212) should make its gaps count as "not recorded" rather than
  service problems.
- **Recorder outages.** 65 minutes from 22:40 on 17 November and 8 minutes from 18:19 on 18 November; already handled
  as "not recorded". The gaps from about 00:15 to 03:30 on weekday nights are normal (no buses run).
- **Other operators' data.** 30 of the 32 detours are on their lines, probably because their unofficial timetables
  have rough route shapes; wrongly learnt brigades create false short turns and cancellations. Trust their results
  less until the box is larger and the learning has more days.

## 4. Suggested order of checks for an unseen or partly seen trip

1. Recording gap, or outside the recording area → **not recorded**.
2. Trip to the depot → left out.
3. Fits a run with no brigade → **run, brigade unknown**.
4. Same bus reappears consistently afterwards → **run, not recorded** (whole trip) or **run, with a gap in its data**
   (part of it).
5. Bus standing still with its delay growing → **stalled**, or **traffic** when several buses stand at one place.
6. Another bus took over → **bus changed** (breakdown, drove off, went silent), or a **breakdown gap** if trips were
   missed in between.
7. Left early, no time gap, smaller delay afterwards → **short-turned**.
8. Otherwise → **missed** (other trips of the brigade ran) or **unknown** (nothing on the brigade).

## 5. How the figures were made

- Abnormal trips: `GET /api/trip-anomalies?from=2026-11-16T02:00:00Z&to=2026-11-23T02:00:00Z`, and the day of every
  brigade with an abnormal trip (`GET /api/brigades/{brigade}/day`, 283 brigade-days).
- Standing still: consecutive reports of a bus in one ~50 m cell on one line and brigade, 10+ minutes, with the delay
  rising by at least 0.7 s per second.
- Stops outside the box: stops of the timetables' trips, against the old box.
- Gaps in recording: minutes with no report at all, 3 or more in a row.
- Runs with no brigade: consecutive reports of a bus with an empty brigade, 20+ minutes.
