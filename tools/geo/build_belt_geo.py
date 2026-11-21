#!/usr/bin/env python3
"""
Builds backend/src/main/resources/places/osm/belt.json: every village (sołectwo) and town reaching within 5 km of
Białystok's city limits, with its gmina and Wikidata item, and the woods in them, from OpenStreetMap (© OpenStreetMap
contributors, ODbL), as fetched by fetch_osm.sh into raw/. Geometry only: the residents are in
places/population/belt.csv, edited by hand (first written by init_belt_population.py).

  tools/geo/fetch_osm.sh
  python3 tools/geo/build_belt_geo.py
"""
import json
import math
import os

from build_bialystok_geo import CITY, shape

HERE = os.path.dirname(os.path.abspath(__file__))
PLACES = os.path.join(HERE, "..", "..", "backend", "src", "main", "resources", "places")
TOWNS = {"Wasilków", "Choroszcz"}


def inside(p, ring):
    y, x = p
    c = False
    for (y1, x1), (y2, x2) in zip(ring, ring[1:]):
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            c = not c
    return c


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
    villages = []
    for e in sorted((e for e in load("belt.json")["elements"] if e["id"] != CITY), key=lambda e: e["tags"]["name"]):
        s = shape(e)
        ring = max(s["outer"], key=len)
        centre = (sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring))
        gmina = next((n for n, gs in gminas if any(inside(centre, r) for r in gs["outer"])), None)
        name = e["tags"]["name"]
        villages.append({"osm": f'relation/{e["id"]}', "name": name, "gmina": gmina,
                         "kind": "town" if name in TOWNS else "village", "wikidata": e["tags"].get("wikidata"),
                         **simplified(s)})
    woods = []
    for e in load("belt-forest.json")["elements"]:
        s = shape(e)
        if s["outer"]:
            woods.append({"osm": f'{e["type"]}/{e["id"]}', "name": e.get("tags", {}).get("name"), **simplified(s)})
    with open(os.path.join(PLACES, "osm", "belt.json"), "w") as f:
        json.dump({"source": "© OpenStreetMap contributors (ODbL), via the Overpass API",
                   "villages": villages, "woods": woods}, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(villages)} villages and towns, {len(woods)} woods")


if __name__ == "__main__":
    main()
