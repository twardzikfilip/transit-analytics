#!/usr/bin/env bash
# Fetches the OpenStreetMap data the places/ files are built from (© OpenStreetMap contributors, ODbL) into
# tools/geo/raw/ (not committed). The Overpass API is shared and often busy: mirrors are tried in turn, with a
# growing wait between rounds. Then run the build scripts (see backend/src/main/resources/places/README.md).
#
#   tools/geo/fetch_osm.sh             everything
#   tools/geo/fetch_osm.sh valley.json  only the named files
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw

CITY=2910925   # Białystok, gmina miejska (the same relation as app.bus-lanes.area-relation)
GMINAS=3092520,3091193,18498121,3092521,2837660,3092524,3091196,1745180   # the gminas around the city
MIRRORS=(https://overpass-api.de/api/interpreter https://overpass.private.coffee/api/interpreter
         https://maps.mail.ru/osm/tools/overpass/api/interpreter)

ONLY=" $* "

fetch() {   # fetch <file> <query>
  if [[ "$ONLY" != "  " && "$ONLY" != *" $1 "* ]]; then return 0; fi
  local out=raw/$1 wait=15
  for _ in 1 2 3 4 5 6; do
    for m in "${MIRRORS[@]}"; do
      if curl -s -m 240 -A "transit-analytics/1.0" "$m" --data-urlencode "data=$2" -o "$out.tmp" \
          && head -c 20 "$out.tmp" | grep -q '^{'; then
        mv "$out.tmp" "$out"; echo "$1 from $m"; return 0
      fi
    done
    echo "$1: servers busy, waiting ${wait}s"; sleep "$wait"; wait=$((wait * 2))
  done
  echo "$1: failed" >&2; return 1
}

WOODS='way["landuse"="forest"](area.s);way["natural"="wood"](area.s);rel["landuse"="forest"](area.s);rel["natural"="wood"](area.s);'

# The city, its districts (osiedla) and the woods within it.
fetch admin.json "[out:json][timeout:120];rel($CITY);out geom;area[\"name\"=\"Białystok\"][\"boundary\"=\"administrative\"][\"admin_level\"=\"8\"]->.c;rel(area.c)[\"boundary\"=\"administrative\"][\"admin_level\"=\"9\"];out geom;"
fetch forest.json "[out:json][timeout:150];rel($CITY);map_to_area->.s;($WOODS);out geom;"
# The belt: every village (sołectwo; tagged admin_level 8 around Białystok) or town reaching within 5 km of the
# city limits, the gminas they are in, and the woods within them.
fetch belt.json "[out:json][timeout:180];rel($CITY);way(r);rel(around:5000)[\"boundary\"=\"administrative\"][\"admin_level\"=\"8\"];out geom;"
fetch gminas.json "[out:json][timeout:180];rel(id:$GMINAS);out geom;"
if [[ "$ONLY" == "  " || "$ONLY" == *" belt-forest.json "* ]]; then
IDS=$(python3 -c "import json; print(','.join(str(e['id']) for e in json.load(open('raw/belt.json'))['elements'] if e['id'] != $CITY))")
fetch belt-forest.json "[out:json][timeout:240];rel(id:$IDS);map_to_area->.s;($WOODS);out geom;"
fi

# The valley of the river Biała: the river in the city, and the open land along it (water, wetland, meadow, grass,
# scrub, parks), from which build_bialystok_geo.py keeps the uninhabited floodplain (see VALLEY_STRETCHES there).
OPEN='["natural"~"^(water|wetland|grassland|scrub|heath)$"]'
GREEN='["landuse"~"^(meadow|grass|reservoir|basin|recreation_ground|village_green)$"]'
# (Overpass's "around" a set of ways returns nothing here, so the land is fetched in the city and kept near the river
# by the build script.)
fetch valley.json "[out:json][timeout:240];rel($CITY);map_to_area->.s;(way[\"waterway\"~\"^(river|canal)$\"][\"name\"=\"Biała\"](area.s);way(area.s)$OPEN;rel(area.s)$OPEN;way(area.s)$GREEN;rel(area.s)$GREEN;way(area.s)[\"leisure\"=\"park\"];rel(area.s)[\"leisure\"=\"park\"];);out geom;"

# The railways in use within about 10 km of the city limits (build_railways.py cuts them).
fetch railways.json "[out:json][timeout:120];way[\"railway\"=\"rail\"](53.0,22.9,53.31,23.42);out tags geom;"

# The railways in use in and around the city (build_railways.py cuts them 10 km from the city limits).
fetch railways.json "[out:json][timeout:120];way[\"railway\"=\"rail\"](52.95,22.85,53.35,23.5);out tags geom;"
