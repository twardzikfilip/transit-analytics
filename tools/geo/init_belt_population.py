#!/usr/bin/env python3
"""
Writes backend/src/main/resources/places/population/belt.csv, the residents of each village and town of the belt,
from the inputs in tools/geo/sources/ (in this order of preference):

  juchnowiec.json  [name, km², residents] per sołectwo of gmina Juchnowiec Kościelny on 31.12.2023: table 1 of its
                   Gminny Program Rewitalizacji do 2035 r. (uchwała IX/84/2025), from the gmina office's register;
  wd.json          Wikidata population (P1082) of each village's Wikidata item (SPARQL answer): the 2021 census;
  plw-pop.json     the 2021 census from polskawliczbach.pl (GUS data), for villages Wikidata lacks;
  wiki-pop.json    the Polish Wikipedia infobox, used only with its year (2011 or later).

belt.csv is then edited by hand (to correct or add a figure), so this refuses to overwrite it without --force.

  python3 tools/geo/init_belt_population.py [--force]
"""
import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PLACES = os.path.join(HERE, "..", "..", "backend", "src", "main", "resources", "places")
OUT = os.path.join(PLACES, "population", "belt.csv")
GMINA_SOURCE = "Gmina Juchnowiec Kościelny, Gminny Program Rewitalizacji do 2035 r. (uchwała IX/84/2025), tab. 1"
HEADER = """# Residents of the villages and towns reaching within 5 km of Białystok's city limits, with the date and source of
# each figure; empty: no figure found. Edit by hand: rows are matched to osm/belt.json by the osm column (a village's
# OpenStreetMap relation). First written by tools/geo/init_belt_population.py from the inputs in tools/geo/sources/.
"""


def main(force):
    if os.path.exists(OUT) and not force:
        sys.exit(f"{OUT} exists and may hold hand edits; use --force to overwrite it")
    load = lambda name: json.load(open(os.path.join(HERE, "sources", name)))
    villages = json.load(open(os.path.join(PLACES, "osm", "belt.json")))["villages"]
    census = {}
    for b in load("wd.json")["results"]["bindings"]:
        date = b.get("date", {}).get("value", "")[:10]
        if "pop" in b and date.startswith("2021"):
            census[b["item"]["value"].rsplit("/", 1)[1]] = (int(float(b["pop"]["value"])), date)
    juch = {name: people for name, _, people in load("juchnowiec.json")}
    plw = {name: found for name, _, found in load("plw-pop.json").values() if found}
    wiki = load("wiki-pop.json")

    rows = []
    for v in villages:
        name = v["name"]
        figure = (None, "", "")
        if v["gmina"] == "gmina Juchnowiec Kościelny" and name in juch:
            figure = (juch[name], "2023-12-31", GMINA_SOURCE)
        elif v.get("wikidata") in census:
            people, date = census[v["wikidata"]]
            figure = (people, date, "NSP 2021 (GUS), via Wikidata " + v["wikidata"])
        elif name in plw:
            figure = (plw[name][0], "2021-03-31", "NSP 2021 (GUS), via " + plw[name][1])
        elif name in wiki and (wiki[name].get("infobox") or "").strip() and (wiki[name].get("year") or "0") >= "2011":
            figure = (int(wiki[name]["infobox"].replace(" ", "").replace(" ", "")), wiki[name]["year"],
                      "Wikipedia (pl): " + wiki[name]["title"])
        rows.append([v["osm"], name, v["gmina"] or "", figure[0] if figure[0] is not None else "", figure[1], figure[2]])

    with open(OUT, "w", newline="") as f:
        f.write(HEADER)
        w = csv.writer(f, quoting=csv.QUOTE_MINIMAL, lineterminator="\n")
        w.writerow(["osm", "name", "gmina", "population", "date", "source"])
        w.writerows(rows)
    known = [r for r in rows if r[3] != ""]
    print(f"{len(rows)} places, {len(known)} with residents; no figure: {[r[1] for r in rows if r[3] == '']}")


if __name__ == "__main__":
    main("--force" in sys.argv[1:])
