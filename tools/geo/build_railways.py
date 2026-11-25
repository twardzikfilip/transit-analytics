#!/usr/bin/env python3
"""
The railways in and around Białystok: builds places/osm/railways.json from raw/railways.json (fetch_osm.sh). Kept are
the lines (main and branch) and the spurs (to depots and factories), in use, up to REACH m from the city limits, and
towards the ends of the City Rail network (ENDS) up to there; yards, sidings and crossovers are left out. Each stretch is [latitude, longitude] points, with the number of its line.
"""
import json
import math
import pathlib

REACH = 10_000
HUB = (53.13357, 23.13540)   # Białystok station
# Łapy, Knyszyn, Czarna Białostocka and Lewickie stations.
ENDS = [(52.98998, 22.88360), (53.28463, 22.89600), (53.30562, 23.27967), (53.02805, 23.12476)]
END_MARGIN = 300             # m beyond an end: the whole station
END_SECTOR = math.radians(15)
HERE = pathlib.Path(__file__).parent
OSM = HERE.parents[1] / "backend/src/main/resources/places/osm"
M_PER_DEG = 111_320
COS = math.cos(math.radians(53.13))

with open(OSM / "bialystok.json") as f:
    city = max(json.load(f)["city"]["outer"], key=len)
RING = [(lon * M_PER_DEG * COS, lat * M_PER_DEG) for lat, lon in city]


def polar(p):
    """Distance and direction from the hub."""
    x, y = (p[1] - HUB[1]) * M_PER_DEG * COS, (p[0] - HUB[0]) * M_PER_DEG
    return math.hypot(x, y), math.atan2(y, x)


def near(lat, lon):
    """Whether a place is kept: before the end it lies towards, else in the city or within REACH of its limits."""
    x, y = lon * M_PER_DEG * COS, lat * M_PER_DEG
    for end in ENDS:
        (d, heading), (d_end, heading_end) = polar((lat, lon)), polar(end)
        if abs((heading - heading_end + math.pi) % (2 * math.pi) - math.pi) < END_SECTOR and d > d_end / 2:
            return d <= d_end + END_MARGIN
    inside = False
    for (x1, y1), (x2, y2) in zip(RING, RING[1:] + RING[:1]):
        dx, dy = x2 - x1, y2 - y1
        k = max(0, min(1, ((x - x1) * dx + (y - y1) * dy) / ((dx * dx + dy * dy) or 1)))
        if math.hypot(x - x1 - k * dx, y - y1 - k * dy) <= REACH:
            return True
        if (y1 > y) != (y2 > y) and x < dx * (y - y1) / dy + x1:
            inside = not inside
    return inside


railways = []
with open(HERE / "raw/railways.json") as f:
    ways = json.load(f)["elements"]
for way in ways:
    tags = way["tags"]
    if tags.get("service") not in (None, "spur"):
        continue
    run = []
    for p in way["geometry"] + [None]:
        if p and near(p["lat"], p["lon"]):
            run.append([round(p["lat"], 5), round(p["lon"], 5)])
            continue
        if len(run) > 1:
            railways.append({"ref": tags.get("ref"), "spur": "service" in tags, "points": run})
        run = []
railways.sort(key=lambda r: (r["spur"], r["ref"] or "", r["points"]))
with open(OSM / "railways.json", "w") as f:
    f.write("[\n" + ",\n".join(json.dumps(r, separators=(",", ":")) for r in railways) + "\n]\n")
print(len(railways), "stretches")
