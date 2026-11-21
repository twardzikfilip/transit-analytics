#!/usr/bin/env python3
"""
Builds the suburban belt for the Network coverage page: every village (sołectwo) and town reaching within 5 km of
Białystok's city limits, with the woods in them, from OpenStreetMap (© OpenStreetMap contributors, ODbL); and their
residents from the sources listed per village.

Overpass API queries (https://overpass-api.de):
  belt.json:        [out:json][timeout:180];rel(2910925);way(r);
                    rel(around:5000)["boundary"="administrative"]["admin_level"="8"];out geom;
                    (OpenStreetMap tags the sołectwa around Białystok admin_level 8; Białystok itself is dropped.)
  gminas.json:      the gminas they lie in: rel(id:3092520,3091193,18498121,3092521,2837660,3092524,3091196,1745180);out geom;
  belt-forest.json: [out:json][timeout:240];rel(id:<the villages>);map_to_area->.s;
                    (way["landuse"="forest"](area.s);way["natural"="wood"](area.s);
                     rel["landuse"="forest"](area.s);rel["natural"="wood"](area.s););out geom;
Residents, in this order of preference:
  juchnowiec.json:  [name, km², residents] per sołectwo of gmina Juchnowiec Kościelny on 31.12.2023, table 1 of the
                    Gminny Program Rewitalizacji (uchwała IX/84/2025), from the gmina office's register;
  wd.json:          Wikidata population (P1082) of each village's OSM wikidata item: the 2021 census (NSP 2021);
  plw-pop.json:     NSP 2021 from polskawliczbach.pl (GUS data) for some villages Wikidata lacks;
  wiki-pop.json:    the Polish Wikipedia infobox, only with its year (2011 or later).

python3 tools/geo/build_belt_geo.py <dir with the files above> backend/src/main/resources/map
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from build_bialystok_geo import shape  # noqa: E402

CITY = 2910925
GMINA_SOURCE = "Gmina Juchnowiec Kościelny, Gminny Program Rewitalizacji do 2035 r. (uchwała IX/84/2025), tab. 1"


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
        ax, ay = pts[a]
        bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        norm = math.hypot(dx, dy) or 1e-9
        best, at = 0.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            d = abs(dy * (px - ax) - dx * (py - ay)) / norm if math.hypot(dx, dy) > 0 else math.hypot(px - ax, py - ay)
            if d > best:
                best, at = d, i
        if best > tolerance_m:
            keep[at] = True
            stack += [(a, at), (at, b)]
    out = [p for p, k_ in zip(ring, keep) if k_]
    return out if len(out) >= 4 else ring


def simplified(s):
    return {"outer": [simplify(r) for r in s["outer"]], "inner": [simplify(r) for r in s["inner"]]}


def main(src, out_dir):
    load = lambda name: json.load(open(os.path.join(src, name)))
    gminas = [(e["tags"]["name"], shape(e)) for e in load("gminas.json")["elements"]]
    villages = [e for e in load("belt.json")["elements"] if e["id"] != CITY]

    wd = {}
    for b in load("wd.json")["results"]["bindings"]:
        if "pop" not in b:
            continue
        q = b["item"]["value"].rsplit("/", 1)[1]
        date = b.get("date", {}).get("value", "")[:10]
        if date.startswith("2021"):
            wd[q] = (int(float(b["pop"]["value"])), date)
    juch = {name: (km2, people) for name, km2, people in load("juchnowiec.json")}
    plw = {name: found for name, _, found in load("plw-pop.json").values() if found}
    wiki = load("wiki-pop.json")

    out_villages, rows = [], []
    for e in sorted(villages, key=lambda e: e["tags"]["name"]):
        t = e["tags"]
        s = shape(e)
        ring = max(s["outer"], key=len)
        centre = (sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring))
        gmina = next((n for n, gs in gminas if any(inside(centre, r) for r in gs["outer"])), None)
        name = t["name"]
        population = date = source = None
        if gmina == "gmina Juchnowiec Kościelny" and name in juch:
            population, date, source = juch[name][1], "2023-12-31", GMINA_SOURCE
        elif t.get("wikidata") in wd:
            population, date = wd[t["wikidata"]]
            source = "NSP 2021 (GUS), via Wikidata " + t["wikidata"]
        elif name in plw:
            population, date, source = plw[name][0], "2021-03-31", "NSP 2021 (GUS), via " + plw[name][1]
        elif name in wiki and (wiki[name].get("infobox") or "").strip() and (wiki[name].get("year") or "0") >= "2011":
            population = int(wiki[name]["infobox"].replace(" ", "").replace(" ", ""))
            date, source = wiki[name]["year"], "Wikipedia (pl): " + wiki[name]["title"]
        kind = "town" if name in ("Wasilków", "Choroszcz") else "village"
        out_villages.append({"osm": f'relation/{e["id"]}', "name": name, "gmina": gmina, "kind": kind,
                             **simplified(s)})
        rows.append((f'relation/{e["id"]}', name, gmina or "", population, date or "", source or ""))

    woods = []
    for e in load("belt-forest.json")["elements"]:
        s = shape(e)
        if s["outer"]:
            woods.append({"osm": f'{e["type"]}/{e["id"]}', "name": e.get("tags", {}).get("name"), **simplified(s)})

    with open(os.path.join(out_dir, "bialystok-belt.json"), "w") as f:
        json.dump({"source": "© OpenStreetMap contributors (ODbL), via the Overpass API",
                   "villages": out_villages, "woods": woods}, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(out_dir, "belt-population.csv"), "w") as f:
        f.write("# Residents of the villages and towns reaching within 5 km of Białystok's city limits; the source and\n"
                "# date of each figure. Empty: no figure found. Built by tools/geo/build_belt_geo.py.\n")
        f.write("osm,name,gmina,population,date,source\n")
        for r in rows:
            f.write(",".join('"%s"' % str(v).replace('"', "'") if v is not None else "" for v in r) + "\n")
    known = [r for r in rows if r[3] is not None]
    print(f"{len(rows)} places, {len(known)} with residents ({sum(r[3] for r in known)}), {len(woods)} woods")
    print("no figure:", [r[1] for r in rows if r[3] is None])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
