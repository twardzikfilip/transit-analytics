"""
The woods and the open land (fields, meadows and the like) in the villages and towns around the city, where nobody
lives. Used by build_belt_geo.py; the inputs are raw/belt-land.json and raw/belt-buildings.json (fetch_osm.sh) and
raw/worldcover.npz (worldcover.py).

How it is worked out, on a grid of CELL m (the one the Network coverage page measures the belt on):
1. What OpenStreetMap says the land is: woods (WOODS), open land (OPEN), or land people live and work on (any other
   landuse: residential, farmyard, industrial, ...). Where these overlap, the last wins.
2. OpenStreetMap has no landuse on a third of the belt, mostly fields. There it is ESA WorldCover's: trees are woods;
   shrubs, grass, crops, wetland and water are open land; but land within YARD m of a building is its yard.
3. Connected pieces (over village borders) of at least MIN_PIECE m² count; their outlines are smoothed by SMOOTH m.
"""
import math

import numpy as np

import biala_valley
from build_bialystok_geo import shape

CELL = 20.0
WOODS = {"forest", "wood"}
OPEN = {"farmland", "meadow", "orchard", "plant_nursery", "vineyard", "grassland", "scrub", "heath", "wetland", "water"}
# Says little about whether people live there: left to the satellites.
VAGUE = {"grass", "railway", "highway", "brownfield", "greenfield"}
TREES, OPEN_COVER = 10, (20, 30, 40, 80, 90)
YARD = 40
MIN_PIECE = 10_000
SMOOTH = 12.0

M_PER_DEG, COS = biala_valley.M_PER_DEG, biala_valley.COS


class Grid(biala_valley.Grid):
    """The valley's grid, with this file's cells."""

    def __init__(self, rings):
        lats = [p[0] for r in rings for p in r]
        lons = [p[1] for r in rings for p in r]
        self.x0 = min(lons) * M_PER_DEG * COS - CELL
        self.y0 = min(lats) * M_PER_DEG - CELL
        self.w = int((max(lons) * M_PER_DEG * COS - self.x0) / CELL) + 2
        self.h = int((max(lats) * M_PER_DEG - self.y0) / CELL) + 2

    def xy(self, ring):
        a = np.array(ring, dtype=float)
        return (a[:, 1] * M_PER_DEG * COS - self.x0) / CELL, (a[:, 0] * M_PER_DEG - self.y0) / CELL

    def latlon(self, x, y):
        return [round((y * CELL + self.y0) / M_PER_DEG, 5), round((x * CELL + self.x0) / (M_PER_DEG * COS), 5)]

    def near_points(self, points, metres):
        """The cells whose centres are within `metres` of a point."""
        out = self.empty()
        r = int(math.ceil(metres / CELL))
        for lat, lon in points:
            px, py = (lon * M_PER_DEG * COS - self.x0) / CELL, (lat * M_PER_DEG - self.y0) / CELL
            for y in range(max(0, int(py) - r), min(self.h, int(py) + r + 1)):
                for x in range(max(0, int(px) - r), min(self.w, int(px) + r + 1)):
                    if math.hypot(x + 0.5 - px, y + 0.5 - py) * CELL <= metres:
                        out[y, x] = True
        return out

    def cover(self, land, box):
        """WorldCover's class at each cell's centre."""
        west, north, step = box
        lon = ((np.arange(self.w) + 0.5) * CELL + self.x0) / (M_PER_DEG * COS)
        lat = ((np.arange(self.h) + 0.5) * CELL + self.y0) / M_PER_DEG
        return land[np.ix_(((north - lat) / step).astype(int), ((lon - west) / step).astype(int))]


def kind(tags):
    k = tags.get("landuse") or tags.get("natural")
    return "woods" if k in WOODS else "open" if k in OPEN else None if k in VAGUE else "used"


def shapes(grid, mask, name):
    """The mask's pieces as one feature per piece: {osm, name, outer, inner}."""
    outer, inner = [], []
    for ring in biala_valley.trace(mask):
        smooth = biala_valley.simplify(ring, SMOOTH / CELL)
        if len(smooth) >= 4:
            (outer if biala_valley.signed_area(smooth) > 0 else inner).append(smooth)
    boxes = [(min(p[0] for p in r), min(p[1] for p in r), max(p[0] for p in r), max(p[1] for p in r)) for r in outer]
    holes = [[] for _ in outer]
    for ring in inner:
        x, y = ring[0]
        # The smallest outline around the hole.
        around = [i for i, b in enumerate(boxes) if b[0] <= x <= b[2] and b[1] <= y <= b[3] and in_ring(x, y, outer[i])]
        if around:
            holes[min(around, key=lambda i: biala_valley.signed_area(outer[i]))].append(ring)
    to = lambda ring: [grid.latlon(x, y) for x, y in ring]
    return [{"osm": f"{name}/{i}", "name": None, "outer": [to(o)], "inner": [to(h) for h in hs]}
            for i, (o, hs) in enumerate(zip(outer, holes))]


def in_ring(x, y, ring):
    hit = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > y) != (y2 > y) and x <= (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            hit = not hit
    return hit


def land(villages, city_rings, elements, buildings, cover, box):
    """(woods, open land) as features, in the villages and outside the city."""
    grid = Grid([r for v in villages for r in v["outer"]])
    area = grid.empty()
    for v in villages:
        area |= grid.fill(v["outer"] + v["inner"])
    area &= ~grid.fill(city_rings)
    mapped = {"woods": grid.empty(), "open": grid.empty(), "used": grid.empty()}
    for e in elements:
        k = kind(e.get("tags", {}))
        s = shape(e) if k else None
        if s and s["outer"]:
            mapped[k] |= grid.fill(s["outer"] + s["inner"])
    used = mapped["used"]
    open_land = mapped["open"] & ~used
    woods = mapped["woods"] & ~used & ~mapped["open"]
    unmapped = area & ~used & ~open_land & ~woods
    free = unmapped & ~grid.near_points(buildings, YARD)
    seen = grid.cover(cover, box)
    woods |= free & (seen == TREES)
    open_land |= free & np.isin(seen, OPEN_COVER)
    cells = int(MIN_PIECE / (CELL * CELL))
    km2 = lambda m: f"{m.sum() * CELL * CELL / 1e6:.1f} km²"
    print(f"area {km2(area)}, without landuse {km2(unmapped)}, of that near buildings {km2(unmapped & ~free)}")
    out = []
    for name, mask in (("woods", woods), ("open", open_land)):
        mask = biala_valley.without_small(mask & area, cells)
        features = shapes(grid, mask, name)
        print(f"{name}: {km2(mask)}, {len(features)} pieces")
        out.append(features)
    return out
