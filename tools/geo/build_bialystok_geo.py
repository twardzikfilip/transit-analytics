#!/usr/bin/env python3
"""
Builds backend/src/main/resources/places/osm/bialystok.json: the city border, its districts (osiedla) and the woods
within it, and the valley of the river Biała (see biala_valley.py), from OpenStreetMap (© OpenStreetMap contributors,
ODbL), as fetched by fetch_osm.sh into raw/.

  tools/geo/fetch_osm.sh
  python3 tools/geo/build_bialystok_geo.py
"""
import json
import os

import biala_valley

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


def main(admin_path, forest_path, valley_path, out_path):
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
        # When OpenStreetMap was in the state fetched (the Overpass answer's timestamp), not when this ran.
        "fetched": json.load(open(admin_path)).get("osm3s", {}).get("timestamp_osm_base", "")[:10],
        "city": {"name": city["tags"]["name"], **shape(city)},
        "districts": [{"osm": f'relation/{d["id"]}', "name": d["tags"]["name"], **shape(d)} for d in districts],
        "forests": forests,
        "valley": biala_valley.valley(json.load(open(valley_path))["elements"], shape(city)["outer"], forests),
    }
    with open(out_path, "w") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f'{len(out["districts"])} districts, {len(forests)} woods, {len(out["valley"])} valley stretches; '
          f'city rings {len(out["city"]["outer"])}')


HERE = os.path.dirname(os.path.abspath(__file__))
PLACES = os.path.join(HERE, "..", "..", "backend", "src", "main", "resources", "places")

if __name__ == "__main__":
    main(os.path.join(HERE, "raw", "admin.json"), os.path.join(HERE, "raw", "forest.json"),
         os.path.join(HERE, "raw", "valley.json"), os.path.join(PLACES, "osm", "bialystok.json"))
