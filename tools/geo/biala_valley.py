"""
The valley of the river Biała where it is wide and nobody lives: the open land along the river (water, wetland,
meadow, grassland, scrub, grass, parks) in the stretches below, inside the city limits and outside its woods.
Used by build_bialystok_geo.py; the input is raw/valley.json (fetch_osm.sh).

How it is worked out, on a grid of CELL m:
1. A stretch is the part of the river inside its box (STRETCHES).
2. An open area (OPEN_KINDS, at least MIN_AREA m²) belongs to a stretch when it comes within TOUCH m of that part of
   the river. EXCLUDED areas never do.
3. The valley is those areas, cut to within REACH m of the river's stretch, to the city limits, and without the woods.
   Pieces smaller than MIN_PIECE m² are dropped.
4. Its outline is traced around the grid cells and smoothed by SMOOTH m.
"""
import math

import numpy as np

CELL = 10.0
OPEN_KINDS = {"water", "wetland", "meadow", "grassland", "scrub", "heath", "grass", "basin", "park"}
MIN_AREA = 1500
TOUCH = 100
REACH = 600
MIN_PIECE = 5000
SMOOTH = 12.0

# Where the valley is wide. Boxes are [south, west, north, east] in degrees; the river inside one is the stretch.
STRETCHES = [
    {
        # From Piastowska (where it meets Branickiego, by the river) down the meadows along Branickiego, past
        # Dojlidy, to the ponds and the city limits.
        "name": "Stawy Dojlidzkie and the meadows along Branickiego",
        "box": [53.08, 23.1815, 53.1262, 23.30],
    },
    {
        # From the western edge of Białostoczek on: Park Antoniuk, Wysoki Stoczek, then north past Bacieczki and
        # Zawady to the city limits.
        "name": "West of Białostoczek",
        "box": [53.12, 23.00, 53.25, 23.1402],
    },
]

# Open areas next to the river that are not its valley.
EXCLUDED = set()

M_PER_DEG = 111320.0
COS = math.cos(math.radians(53.13))


class Grid:
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

    def empty(self):
        return np.zeros((self.h, self.w), dtype=bool)

    def fill(self, rings):
        """The cells whose centres are inside the rings (even-odd, so holes are holes)."""
        out = self.empty()
        edges = []
        for ring in rings:
            x, y = self.xy(ring)
            edges.append(np.stack([x[:-1], y[:-1], x[1:], y[1:]], axis=1))
        if not edges:
            return out
        e = np.concatenate(edges)
        lo = max(0, int(math.floor(min(e[:, 1].min(), e[:, 3].min()))))
        hi = min(self.h - 1, int(math.ceil(max(e[:, 1].max(), e[:, 3].max()))))
        for row in range(lo, hi + 1):
            yc = row + 0.5
            cross = (e[:, 1] <= yc) != (e[:, 3] <= yc)
            if not cross.any():
                continue
            c = e[cross]
            xs = np.sort(c[:, 0] + (yc - c[:, 1]) * (c[:, 2] - c[:, 0]) / (c[:, 3] - c[:, 1]))
            for a, b in zip(xs[0::2], xs[1::2]):
                first, last = max(0, int(math.ceil(a - 0.5))), min(self.w - 1, int(math.floor(b - 0.5)))
                if first <= last:
                    out[row, first:last + 1] = True
        return out

    def near(self, lines, metres):
        """The cells within `metres` of the lines."""
        out = self.empty()
        r = int(math.ceil(metres / CELL))
        yy, xx = np.mgrid[-r:r + 1, -r:r + 1]
        disc = xx * xx + yy * yy <= (metres / CELL) ** 2
        for line in lines:
            x, y = self.xy(line)
            for i in range(len(x) - 1):
                steps = max(1, int(math.hypot(x[i + 1] - x[i], y[i + 1] - y[i])))
                for t in np.linspace(0, 1, steps + 1):
                    cx = int(round(x[i] + t * (x[i + 1] - x[i])))
                    cy = int(round(y[i] + t * (y[i + 1] - y[i])))
                    y1, y2, x1, x2 = cy - r, cy + r + 1, cx - r, cx + r + 1
                    if y2 <= 0 or x2 <= 0 or y1 >= self.h or x1 >= self.w:
                        continue
                    d = disc[max(0, -y1):disc.shape[0] - max(0, y2 - self.h), max(0, -x1):disc.shape[1] - max(0, x2 - self.w)]
                    out[max(0, y1):min(self.h, y2), max(0, x1):min(self.w, x2)] |= d
        return out


def in_box(p, box):
    return box[0] <= p[0] <= box[2] and box[1] <= p[1] <= box[3]


def stretch_lines(river, box):
    """The parts of the river inside the box."""
    parts = []
    for line in river:
        run = []
        for p in line:
            if in_box(p, box):
                run.append(p)
            else:
                if len(run) > 1:
                    parts.append(run)
                run = []
        if len(run) > 1:
            parts.append(run)
    return parts


def without_small(mask, min_cells):
    """Drops the connected pieces (4-neighbours) of fewer than min_cells cells."""
    seen = np.zeros_like(mask)
    out = mask.copy()
    h, w = mask.shape
    for sy, sx in zip(*np.nonzero(mask)):
        if seen[sy, sx]:
            continue
        stack, piece = [(sy, sx)], []
        seen[sy, sx] = True
        while stack:
            y, x = stack.pop()
            piece.append((y, x))
            for ny, nx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    stack.append((ny, nx))
        if len(piece) < min_cells:
            for y, x in piece:
                out[y, x] = False
    return out


def trace(mask):
    """The outlines of the set cells, as closed rings of grid corners: outer rings anticlockwise, holes clockwise."""
    m = np.pad(mask, 1)
    nxt = {}

    def edge(a, b):
        nxt.setdefault(a, []).append(b)

    for y, x in zip(*np.nonzero(m)):
        gx, gy = x - 1, y - 1  # back to the unpadded grid's corners
        if not m[y - 1, x]:
            edge((gx, gy), (gx + 1, gy))
        if not m[y, x + 1]:
            edge((gx + 1, gy), (gx + 1, gy + 1))
        if not m[y + 1, x]:
            edge((gx + 1, gy + 1), (gx, gy + 1))
        if not m[y, x - 1]:
            edge((gx, gy + 1), (gx, gy))
    rings = []
    while nxt:
        start = next(iter(nxt))
        ring, at = [start], start
        while True:
            ends = nxt[at]
            to = ends.pop()
            if not ends:
                del nxt[at]
            if to == start:
                break
            ring.append(to)
            at = to
        ring.append(start)
        rings.append(ring)
    return rings


def simplify(points, tolerance):
    """Douglas-Peucker on an open polyline."""
    if len(points) < 3:
        return points
    a, b = np.array(points[0], float), np.array(points[-1], float)
    ab = b - a
    n = np.hypot(*ab)
    pts = np.array(points[1:-1], float)
    if n == 0:
        d = np.hypot(*(pts - a).T)
    else:
        d = np.abs(ab[0] * (pts[:, 1] - a[1]) - ab[1] * (pts[:, 0] - a[0])) / n
    i = int(np.argmax(d))
    if d[i] <= tolerance:
        return [points[0], points[-1]]
    left = simplify(points[:i + 2], tolerance)
    return left[:-1] + simplify(points[i + 1:], tolerance)


def signed_area(ring):
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:])) / 2


def valley(raw, city_rings, woods):
    """The valley, one feature per stretch: {name, outer, inner} with rings of [lat, lon]. `woods`: {outer, inner}."""
    river = [[[p["lat"], p["lon"]] for p in e["geometry"]] for e in raw if e.get("tags", {}).get("waterway")]
    open_land = [e for e in raw if not e.get("tags", {}).get("waterway")]
    grid = Grid(city_rings)
    city = grid.fill(city_rings)
    wooded = grid.empty()
    for w in woods:  # each on its own: overlapping woods would cancel out in one even-odd fill
        wooded |= grid.fill(w["outer"] + w["inner"])
    from build_bialystok_geo import shape

    areas = []
    for e in open_land:
        tags = e.get("tags", {})
        kind = tags.get("natural") or tags.get("landuse") or tags.get("leisure")
        osm = f'{e["type"]}/{e["id"]}'
        if kind not in OPEN_KINDS or osm in EXCLUDED:
            continue
        s = shape(e)
        if not s["outer"]:
            continue
        cells = grid.fill(s["outer"] + s["inner"])
        if cells.sum() * CELL * CELL >= MIN_AREA:
            areas.append(cells)

    features, taken = [], grid.empty()
    for stretch in STRETCHES:
        lines = stretch_lines(river, stretch["box"])
        touch, reach = grid.near(lines, TOUCH), grid.near(lines, REACH)
        mask = grid.empty()
        for cells in areas:
            if (cells & touch).any():
                mask |= cells
        mask &= reach & city & ~wooded & ~taken
        mask = without_small(mask, int(MIN_PIECE / (CELL * CELL)))
        taken |= mask
        outer, inner = [], []
        for ring in trace(mask):
            smooth = simplify(ring, SMOOTH / CELL)
            if len(smooth) < 4:
                continue
            (outer if signed_area(smooth) > 0 else inner).append([grid.latlon(x, y) for x, y in smooth])
        features.append({"name": stretch["name"], "outer": outer, "inner": inner})
        print(f'valley: {stretch["name"]}: {mask.sum() * CELL * CELL / 1e6:.2f} km², {len(outer)} pieces')
    return features
