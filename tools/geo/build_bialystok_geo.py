#!/usr/bin/env python3
"""
Builds backend/src/main/resources/map/bialystok-areas.json, the shapes the Network coverage page uses: the city border,
its districts (osiedla) and the woods within it, from OpenStreetMap (© OpenStreetMap contributors, ODbL).

Get the two Overpass API answers first (https://overpass-api.de; be gentle, it's a shared server):

  admin.json:  [out:json][timeout:120];rel(2910925);out geom;
               area["name"="Białystok"]["boundary"="administrative"]["admin_level"="8"]->.c;
               rel(area.c)["boundary"="administrative"]["admin_level"="9"];out geom;
  forest.json: [out:json][timeout:150];rel(2910925);map_to_area->.c;
               (way["landuse"="forest"](area.c);way["natural"="wood"](area.c);
                rel["landuse"="forest"](area.c);rel["natural"="wood"](area.c););out geom;

then: python3 tools/geo/build_bialystok_geo.py admin.json forest.json backend/src/main/resources/map/bialystok-areas.json
"""
import json
import sys
from datetime import date

CITY = 2910925  # Białystok, admin_level 8


def pt(p):
    return [round(p["lat"], 5), round(p["lon"], 5)]


def stitch(ways):
    """Joins way geometries end to end into closed rings; drops what can't be closed."""
    ways = [[pt(p) for p in w] for w in ways if len(w) > 1]
    rings = []
    while ways:
        ring = ways.pop(0)
        changed = True
        while ring[0] != ring[-1] and changed:
            changed = False
            for i, w in enumerate(ways):
                if w[0] == ring[-1]:
                    ring += w[1:]
                elif w[-1] == ring[-1]:
                    ring += w[::-1][1:]
                elif w[-1] == ring[0]:
                    ring = w[:-1] + ring
                elif w[0] == ring[0]:
                    ring = w[::-1][:-1] + ring
                else:
                    continue
                ways.pop(i)
                changed = True
                break
        if ring[0] == ring[-1] and len(ring) >= 4:
            rings.append(ring)
    return rings


def shape(element):
    if element["type"] == "way":
        return {"outer": stitch([element["geometry"]]), "inner": []}
    members = [m for m in element.get("members", []) if m["type"] == "way" and "geometry" in m]
    return {"outer": stitch([m["geometry"] for m in members if m.get("role") in ("outer", "")]),
            "inner": stitch([m["geometry"] for m in members if m.get("role") == "inner"])}


def main(admin_path, forest_path, out_path):
    admin = json.load(open(admin_path))["elements"]
    city = next(e for e in admin if e["id"] == CITY)
    districts = sorted((e for e in admin if e["tags"].get("admin_level") == "9"), key=lambda e: e["tags"]["name"])
    forests = []
    for e in json.load(open(forest_path))["elements"]:
        s = shape(e)
        if not s["outer"]:
            continue
        tags = e.get("tags", {})
        forests.append({"osm": f'{e["type"]}/{e["id"]}', "name": tags.get("name"), **s})
    out = {
        "source": "© OpenStreetMap contributors (ODbL), via the Overpass API",
        "fetched": date.today().isoformat(),
        "city": {"name": city["tags"]["name"], **shape(city)},
        "districts": [{"osm": f'relation/{d["id"]}', "name": d["tags"]["name"], **shape(d)} for d in districts],
        "forests": forests,
    }
    with open(out_path, "w") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f'{len(out["districts"])} districts, {len(forests)} woods; city rings {len(out["city"]["outer"])}')


if __name__ == "__main__":
    main(*sys.argv[1:4])
