# Traffic camera analysis (kamery.szr.bialystok.pl/kam12): feasibility and prototype plan

## Context
The user asked whether information can be extracted from the camera stream at
https://kamery.szr.bialystok.pl/kam12/, and how:
- traffic signal cycles
- how many vehicles pass through the intersection
- details about each vehicle that is recognised

They also asked which language and technology to use. This file holds the analysis and a proposed prototype. Nothing
has been built yet.

## What the stream is (measured 2026-11-17)

**Delivery**
- `Content-Type: multipart/x-mixed-replace;boundary=ffserver`: plain **MJPEG over HTTPS**, served by ffserver behind
  nginx.
- No player page. The URL itself is the endless stream.
- Each frame is a full JPEG of about 34 KB, so the stream is about 270 KB/s, roughly 1 TB a year if recorded raw.

**Frames**
- 640×640.
- Exactly **8.0 fps**: 240 unique frames in 30 s.
- Arrival jitter up to 0.37 s.
- No timestamp overlay. Times come from arrival.

**TLS**
- The server omits its Let's Encrypt intermediate, YR2.
- YR2 chains to the new "ISRG Root YR", which this Debian trust store lacks. That root is cross-signed by ISRG Root
  X1.
- A client must ship that chain (YR2 + Root YR cross-signed by X1) as an extra CA bundle. Verification stays on;
  don't disable it.

**View**
- A fixed wide-angle (slightly fisheye) camera looking down at one corner of a large intersection.
- Visible: multi-lane approaches, a stop line, crosswalks, and BKM buses (green livery) together with cars, taxis and
  pedestrians.
- **No signal heads are visible.**

**Index**
- `https://kamery.szr.bialystok.pl/` is the default nginx/EPEL test page.
- No camera list and no terms of use are published there. The camera's location has to come from the user or the
  city.

## Feasibility per question

### 1. Vehicle counts: yes, reliable
- Run a detector and tracker on every frame, then count tracks that cross virtual lines placed per approach and
  per exit.
- Entry approach to exit gives **turning movements** (an origin–destination matrix).
- Expected accuracy is about 90–95% in daylight. It drops at night, in rain or glare, and when buses hide other
  vehicles.
- 8 fps is enough to track vehicles at urban speeds.

### 2. Signal cycles: yes, but inferred, not seen
The lights aren't in view, so phases are reconstructed from behaviour:
- **Per approach, a stop-line zone:** vehicles stationary in the queue zone, versus vehicles crossing the stop line.
  - The **start of green** is when the queue starts to discharge.
  - The **start of red** is when crossings stop and the queue builds.
- **Pedestrian surges on the crosswalks** mark the pedestrian green.
- **Cycle length** comes from the autocorrelation or FFT of crossings over about 30 min.

Białystok's SZR is **adaptive**, so cycles change. Estimate each cycle on its own rather than one fixed plan.

Accuracy:
- Good in moderate or heavy traffic.
- Poor at night with no vehicles, since an empty green looks the same as a red.
- An approach not visible in the frame can only be inferred from the conflicting flows.

### 3. Per-vehicle details: partly
Feasible:
- **Class:** car, bus, truck, motorcycle, bicycle; vans with a fine-tuned model.
- **Colour.**
- **Trajectory:** approach, exit, turning movement.
- **Time** of entry and exit.
- **Waiting time** at the stop line.
- **Rough speed.** This needs a homography from the lane-marking dimensions and fisheye undistortion first; expect
  about ±10–15%.
- **Size class.**

BKM buses can be **matched to a vehicle number** with the GPS positions this app already records, by time and place
near the intersection. That gives each bus its number, line, brigade and delay, plus how long it waited at the lights.

Not feasible, or not advisable:
- **Number plates.** At 640 px wide-angle a plate is about 15–30 px, too small to read reliably. Plates are also
  personal data under GDPR, and reading them needs a legal basis.
- **Make and model.** Possible for close vehicles with a fine-tuned classifier, but unreliable at this resolution.
- **Faces and people tracking.** Don't do it. Count pedestrians only.

## Technology recommendation

### Language and stack
Use **Python** for the vision part. That's where the mature tools are:
- **Ultralytics YOLO11** (`yolo11n`/`s`, COCO classes) with its built-in **ByteTrack** tracker.
- **supervision**: `LineZone` counting, `PolygonZone` occupancy, and heatmaps.
- **OpenCV**: undistortion and homography.
- The MJPEG is read with a small chunked reader (boundary split, as in the measurement), or `cv2.VideoCapture`.
- Java options (DJL, JavaCV) work, but their tooling is far behind. Keep Java for the API.

### Hardware
- One camera at 8 fps runs in real time **on CPU** with `yolo11n` at 640 px.
- Add a GPU only for several cameras or larger models.

### How it fits this repo
- A new `camera-analytics/` Python service in `docker-compose.yml`.
- It writes **events, not video**, to TimescaleDB, in a new Flyway migration:
  - `camera_crossing` (camera, time, track_id, class, colour, approach, exit, speed, wait_s)
  - `camera_phase` (camera, approach, green_start, red_start)
- Spring Boot serves them on a new page, reusing the existing patterns: `ServiceDay`, `TimeSeriesChart`, and the
  position history for matching buses.
- Store a few sample frames for debugging, not continuous video. That avoids the storage problem and most of the
  privacy concerns.

## Proposed prototype (if the user wants it built)
1. **Recorder spike (Python)**
   - Read the stream with the verified CA chain.
   - Save 10 minutes of frames with arrival timestamps.
   - Reconnect with backoff, using one connection only.
2. **Calibration**
   - Draw approach, exit and stop-line polygons on a still frame, saved as a JSON config per camera.
   - Fisheye undistortion and a homography from lane-marking lengths.
3. **Detection and counting**
   - YOLO11 + ByteTrack + supervision line zones.
   - Validate by counting 5 minutes by hand.
4. **Signal phases**
   - Queue and discharge per approach, then the cycle estimate.
   - Validate by timing a few cycles on site, or from a phone video of the lights.
5. **Storage and API**
   - The migration, the Python writer, and a Spring endpoint with a page: counts per 15 min, turning matrix, cycle
     lengths, waits.
6. **Bus matching** with `position_sample` by time and location.

## Open points for the user
- **Location.** Which intersection is kam12? Needed to place it on the map and match buses.
- **Permission.** The city (ZDiT/SZR Białystok) publishes the stream without terms. Ask before continuous automated
  processing, and before publishing results.
- **Hardware.** Is a GPU available, or CPU only?

## Verification (for the prototype)
- Hand counts against automatic counts over a 5–10 minute clip, per approach; aim for ≤5% error.
- Inferred green and red starts against a phone recording of the lights, aiming for ≤3 s.
- Matched BKM buses against `position_sample` vehicle numbers.
- Run 24 hours and check the stream reconnects, stays at 8 fps and has no event gaps.
