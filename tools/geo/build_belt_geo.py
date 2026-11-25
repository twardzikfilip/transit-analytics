#!/usr/bin/env python3
"""
Builds backend/src/main/resources/places/osm/belt.json: the villages (sołectwa) and towns around Białystok, with their
gmina and Wikidata item, and the woods and open land of at least 1 ha in them (build_belt_land.py), from OpenStreetMap
(© OpenStreetMap contributors, ODbL), as fetched by fetch_osm.sh into raw/, and ESA WorldCover (worldcover.py).
Geometry only: the residents are in places/population/belt.csv, edited by hand (first written by
init_belt_population.py).

A place is in the belt when it reaches within NEAR m of the city limits, or within FAR m in the arc from Supraśl round
the south to Baciuty (bearings ARC of its middle from the city's).

  tools/geo/fetch_osm.sh
  python3 tools/geo/worldcover.py
  python3 tools/geo/build_belt_geo.py
"""
import json
import math
import os

import numpy as np

from build_belt_land import land
from build_bialystok_geo import CITY, shape

HERE = os.path.dirname(os.path.abspath(__file__))
PLACES = os.path.join(HERE, "..", "..", "backend", "src", "main", "resources", "places")
TOWNS = {"Wasilków", "Choroszcz", "Supraśl", "Zabłudów"}
NEAR, FAR = 5_000, 9_000
ARC = (50, 242)
# Places whose residents are counted together: measured as one, under the first one's name.
TOGETHER = {"Porosły": "Porosły-Kolonia", "Dzikie": "Dzikie-Kolonia", "Lewickie-Stacja": "Kojrany"}
GRODEK = {"Kołodno"}  # in gmina Gródek, whose shape isn't fetched


def inside(p, ring):
    y, x = p
    c = False
    for (y1, x1), (y2, x2) in zip(ring, ring[1:]):
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            c = not c
    return c


def metres(p):
    return p[1] * math.cos(math.radians(53.13)) * 111_320, p[0] * 111_320


def distance(ring, city):
    """From a place's outline to the city limits; 0 when they overlap."""
    if any(inside(p, city) for p in ring):
        return 0
    best = float("inf")
    for p in ring:
        x, y = metres(p)
        for (x1, y1), (x2, y2) in zip(map(metres, city), map(metres, city[1:])):
            dx, dy = x2 - x1, y2 - y1
            k = max(0, min(1, ((x - x1) * dx + (y - y1) * dy) / ((dx * dx + dy * dy) or 1)))
            best = min(best, math.hypot(x - x1 - k * dx, y - y1 - k * dy))
    return best


def middle(ring):
    return sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring)


def in_belt(ring, city):
    d = distance(ring, city)
    if d <= NEAR:
        return True
    (x, y), (cx, cy) = metres(middle(ring)), metres(middle(city))
    return d <= FAR and ARC[0] <= math.degrees(math.atan2(x - cx, y - cy)) % 360 <= ARC[1]


def simplify(ring, tolerance_m=3.0):
    """Douglas–Peucker in metres (a flat projection is fine at this scale); keeps rings closed."""
    if len(ring) < 5:
        return ring
    k = math.cos(math.radians(53.13)) * 111_320
    pts = [(p[1] * k, p[0] * 111_320) for p in ring]
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        (ax, ay), (bx, by) = pts[a], pts[b]
        norm = math.hypot(bx - ax, by - ay)
        best, at = 0.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            d = (abs((by - ay) * (px - ax) - (bx - ax) * (py - ay)) / norm) if norm > 0 else math.hypot(px - ax, py - ay)
            if d > best:
                best, at = d, i
        if best > tolerance_m:
            keep[at] = True
            stack += [(a, at), (at, b)]
    out = [p for p, kept in zip(ring, keep) if kept]
    return out if len(out) >= 4 else ring


def simplified(s):
    return {"outer": [simplify(r) for r in s["outer"]], "inner": [simplify(r) for r in s["inner"]]}


def main():
    load = lambda name: json.load(open(os.path.join(HERE, "raw", name)))
    gminas = [(e["tags"]["name"], shape(e)) for e in load("gminas.json")["elements"]]
    with open(os.path.join(PLACES, "osm", "bialystok.json")) as f:
        city_rings = json.load(f)["city"]["outer"]
    city = max(city_rings, key=len)
    with open(os.path.join(PLACES, "osm", "belt.json")) as f:
        before = {v["osm"] for v in json.load(f)["villages"]}
    villages = []
    for e in sorted((e for e in load("belt.json")["elements"] if e["id"] != CITY), key=lambda e: e["tags"]["name"]):
        s = shape(e)
        ring = max(s["outer"], key=len)
        if not in_belt(ring, city):
            continue
        centre = middle(ring)
        name = e["tags"]["name"]
        gmina = next((n for n, gs in gminas if any(inside(centre, r) for r in gs["outer"])),
                     "gmina Gródek" if name in GRODEK else None)
        villages.append({"osm": f'relation/{e["id"]}', "name": name, "gmina": gmina,
                         "kind": "town" if name in TOWNS else "village", "wikidata": e["tags"].get("wikidata"),
                         **simplified(s)})
    for name, other in TOGETHER.items():
        place, part = (next(v for v in villages if v["name"] == n) for n in (name, other))
        place["outer"] += part["outer"]
        place["inner"] += part["inner"]
        place["name"] = f"{name}, {other}"
        villages.remove(part)
    cover = np.load(os.path.join(HERE, "raw", "worldcover.npz"))
    buildings = [(e["center"]["lat"], e["center"]["lon"]) for e in load("belt-buildings.json")["elements"]]
    woods, open_land = land(villages, city_rings, load("belt-land.json")["elements"], buildings, cover["land"],
                            cover["box"])
    with open(os.path.join(PLACES, "osm", "belt.json"), "w") as f:
        json.dump({"source": "© OpenStreetMap contributors (ODbL), via the Overpass API; ESA WorldCover 2021 (CC BY 4.0)",
                   "villages": villages, "woods": woods, "open": open_land}, f, ensure_ascii=False,
                  separators=(",", ":"))
    now = {v["osm"] for v in villages}
    print("new:", sorted(v["name"] for v in villages if v["osm"] not in before), "gone:", sorted(before - now))
    print(f"{len(villages)} villages and towns")


if __name__ == "__main__":
    main()
