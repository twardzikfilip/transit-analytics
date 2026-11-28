#!/usr/bin/env python3
"""
The network of City Rail proposal no. 1: builds places/osm/city-rail-proposal.json from raw/railways.json and
raw/rail-streets.json (fetch_osm.sh). Five railways from Białystok station, each along its railway line (the shortest
way on the track, keeping to that line where it can), shortened to the proposal's ends, with their stations: those
served today (with their id in the timetable), those no train calls at today (Białystok Fabryczny), those the city
trains pass without stopping (Fasty), and the new ones: each just past a street crossing (looking away from Białystok),
where two streets meet, or nearest to a bus stop.
"""
import heapq
import json
import math
import pathlib

HERE = pathlib.Path(__file__).parent
OSM = HERE.parents[1] / "backend/src/main/resources/places/osm"
M_PER_DEG = 111_320
COS = math.cos(math.radians(53.13))
# A new station's middle lies this far past its crossing: about half a platform for a short train.
PAST_CROSSING = 100

HUB = {"id": "24000", "name": "Białystok", "at": (53.13362, 23.13544)}


def served(gtfs_id, name, at):
    return {"id": gtfs_id, "name": name, "at": at, "kind": "served"}


def unserved(name, at):
    return {"id": None, "name": name, "at": at, "kind": "unserved"}


def crossing(name, street, near):
    """A new station just past the crossing with a street (the one of that name nearest to `near`)."""
    return {"id": None, "name": name, "street": street, "near": near, "kind": "new"}


def nearest(name, point):
    """A new station at the place on the track nearest to a point (a bus stop it would serve)."""
    return {"id": None, "name": name, "near": point, "kind": "new"}


def passed(gtfs_id, name, at):
    """A station trains call at today that the city trains run through without stopping."""
    return {"id": gtfs_id, "name": name, "at": at, "kind": "passed"}


def meeting(name, streets):
    """A new station by the place where two streets meet."""
    return {"id": None, "name": name, "streets": streets, "kind": "new"}


# Per railway: its id, the railway line it keeps to, its stations from Białystok outwards (the last is the end), and,
# where the end is a new station, the station past it: the track is followed up to that one, and (when trains call
# there) their run time to it from the station before the end is shared out by distance.
CORRIDORS = [
    ("niewodnica", "6", [
        served("279919", "Białystok Zielone Wzgórza", (53.11550, 23.10301)),
        served("24612", "Klepacze", (53.10228, 23.07984)),
        served("24596", "Niewodnica", (53.08276, 23.04586)),
    ]),
    ("dobrzyniewo", "38", [
        served("279919", "Białystok Zielone Wzgórza", (53.11550, 23.10301)),
        served("24042", "Białystok Starosielce", (53.12119, 23.08678)),
        crossing("Białystok Leśna Dolina", "Księdza Jerzego Popiełuszki", (53.1293, 23.0808)),
        served("24059", "Białystok Bacieczki", (53.15052, 23.07199)),
        # By the bus stop Fasty/Mleczarnia.
        nearest("Fasty Osada", (53.171235, 23.046483)),
        passed("24067", "Fasty", (53.17672, 23.02504)),
        served("24075", "Dobrzyniewo Duże", (53.19435, 23.00275)),
    ]),
    ("juchnowiec", "32", [
        served("279919", "Białystok Zielone Wzgórza", (53.11550, 23.10301)),
        served("264544", "Białystok Nowe Miasto", (53.10645, 23.11371)),
        served("24372", "Białystok Stadion", (53.09780, 23.12954)),
        crossing("Ignatki-Osiedle", "Leśna", (53.0869, 23.1268)),
        served("264545", "Hryniewicze", (53.07272, 23.12808)),
        served("24364", "Lewickie", (53.02788, 23.12482)),
        # Where Dworska and Stanisława Włoszka streets cross the railway.
        crossing("Juchnowiec Dolny", "Dworska", (53.0084, 23.1247)),
    ], served("24380", "Hołówki Duże", (52.97137, 23.09528))),
    ("wasilkow", "6", [
        meeting("Białystok Białostoczek", ("Sitarska", "Sokólska")),
        served("24190", "Wasilków", (53.19832, 23.17704)),
    ]),
    ("henrykowo", "37", [
        # Between Przytorowa and Poleska streets, just past the railway's bridge over Jurowiecka street.
        crossing("Białystok Jurowiecka", "Jurowiecka", (53.1414, 23.1582)),
        unserved("Białystok Fabryczny", (53.14052, 23.18419)),
        crossing("Białystok Pieczurki", "Piasta", (53.1331, 23.2054)),
        crossing("Białystok Zaścianki", "Szosa Baranowicka", (53.1250, 23.2295)),
        crossing("Sobolewo", "Sobolewska", (53.1185, 23.2641)),
        # The crossing with Podleśna street north of the village, before Kuriany station.
        crossing("Henrykowo", "Podleśna", (53.1157, 23.2953)),
    ], unserved("Kuriany", (53.11122, 23.32137))),
]


def xy(p):
    return p[1] * M_PER_DEG * COS, p[0] * M_PER_DEG


def meters(a, b):
    (ax, ay), (bx, by) = xy(a), xy(b)
    return math.hypot(ax - bx, ay - by)


def track(ways, ref, start, end):
    """The shortest way on the track from start to end: off the railway line `ref` it counts 4 times its length, on a
    siding or in a yard 10 times, so it keeps to the line wherever the line goes through."""
    adj = {}
    for way in ways:
        tags = way["tags"]
        cost = 10 if tags.get("service") else 1 if tags.get("ref") == ref else 4
        g = [(p["lat"], p["lon"]) for p in way["geometry"]]
        for a, b in zip(g, g[1:]):
            m = meters(a, b)
            adj.setdefault(a, []).append((b, m * cost))
            adj.setdefault(b, []).append((a, m * cost))
    s = min(adj, key=lambda n: meters(n, start))
    e = min(adj, key=lambda n: meters(n, end))
    dist, prev, queue = {s: 0}, {}, [(0, s)]
    while queue:
        d, n = heapq.heappop(queue)
        if n == e:
            break
        if d > dist[n]:
            continue
        for m, c in adj[n]:
            if d + c < dist.get(m, math.inf):
                dist[m], prev[m] = d + c, n
                heapq.heappush(queue, (d + c, m))
    path = [e]
    while path[-1] != s:
        path.append(prev[path[-1]])
    return path[::-1]


def chainage(points):
    out = [0.0]
    for a, b in zip(points, points[1:]):
        out.append(out[-1] + meters(a, b))
    return out


def project(points, chain, p):
    """The distance along the track of the point nearest to p, and how far p is from it."""
    best = (math.inf, 0.0)
    px, py = xy(p)
    for i, (a, b) in enumerate(zip(points, points[1:])):
        (ax, ay), (bx, by) = xy(a), xy(b)
        dx, dy = bx - ax, by - ay
        k = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / ((dx * dx + dy * dy) or 1)))
        off = math.hypot(px - ax - k * dx, py - ay - k * dy)
        if off < best[0]:
            best = (off, chain[i] + k * (chain[i + 1] - chain[i]))
    return best[1], best[0]


def at(points, chain, km):
    """The point at a distance along the track."""
    for i in range(1, len(chain)):
        if chain[i] >= km:
            k = (km - chain[i - 1]) / ((chain[i] - chain[i - 1]) or 1)
            a, b = points[i - 1], points[i]
            return a[0] + k * (b[0] - a[0]), a[1] + k * (b[1] - a[1])
    return points[-1]


def crossings(points, chain, street_ways):
    """Distances along the track at which it crosses (over or under) the streets."""
    found = []
    for way in street_ways:
        g = [(p["lat"], p["lon"]) for p in way["geometry"]]
        for c, d in zip(g, g[1:]):
            (cx, cy), (dx, dy) = xy(c), xy(d)
            for i, (a, b) in enumerate(zip(points, points[1:])):
                (ax, ay), (bx, by) = xy(a), xy(b)
                den = (bx - ax) * (dy - cy) - (by - ay) * (dx - cx)
                if den == 0:
                    continue
                t = ((cx - ax) * (dy - cy) - (cy - ay) * (dx - cx)) / den
                u = ((cx - ax) * (by - ay) - (cy - ay) * (bx - ax)) / den
                if 0 <= t <= 1 and 0 <= u <= 1:
                    found.append(chain[i] + t * (chain[i + 1] - chain[i]))
    return found


def main():
    with open(HERE / "raw/railways.json") as f:
        ways = json.load(f)["elements"]
    with open(HERE / "raw/rail-streets.json") as f:
        streets = json.load(f)["elements"]
    corridors = []
    for cid, ref, stations, *beyond in CORRIDORS:
        beyond = beyond[0] if beyond else None
        points = track(ways, ref, HUB["at"], (beyond or stations[-1])["at"])
        chain = chainage(points)
        placed = []
        for s in [HUB] + stations:
            if "street" in s:
                named = [w for w in streets if w["tags"].get("name") == s["street"]]
                hint, _ = project(points, chain, s["near"])
                xs = crossings(points, chain, named)
                if not xs or min(abs(x - hint) for x in xs) > 150:
                    # A street passing under a bridge may not touch the track's line in the data: its nearest point.
                    xs = [hint]
                km = min(xs, key=lambda x: abs(x - hint)) + PAST_CROSSING
                lat, lon = at(points, chain, km)
            elif "near" in s:
                km, off = project(points, chain, s["near"])
                lat, lon = at(points, chain, km)
            elif "streets" in s:
                a, b = ([(p["lat"], p["lon"]) for w in streets if w["tags"].get("name") == n for p in w["geometry"]]
                        for n in s["streets"])
                meet = min(((p, q) for p in a for q in b), key=lambda pq: meters(*pq))[0]
                km, _ = project(points, chain, meet)
                lat, lon = at(points, chain, km)
            else:
                km, off = project(points, chain, s["at"])
                # A timetable's point for a station may be its building, off the track.
                assert off < 250, f"{s['name']} is {off:.0f} m off the track to {cid}"
                lat, lon = s["at"]
            placed.append({"id": s["id"], "name": s["name"], "kind": s.get("kind", "served"),
                           "lat": round(lat, 5), "lon": round(lon, 5), "km": round(km / 1000, 3)})
        kms = [p["km"] for p in placed]
        assert kms == sorted(kms), f"{cid}: stations out of order {kms}"
        # The track up to the end station.
        end = placed[-1]["km"] * 1000
        cut = [p for p, c in zip(points, chain) if c <= end] + [at(points, chain, end)]
        corridor = {"id": cid, "ref": ref, "stations": placed,
                    "points": [[round(p[0], 5), round(p[1], 5)] for p in cut]}
        if beyond:
            km, off = project(points, chain, beyond["at"])
            assert off < 250 and km > end, f"{beyond['name']} isn't on the track past the end of {cid}"
            corridor["beyond"] = {"id": beyond["id"], "name": beyond["name"], "km": round(km / 1000, 3)}
        corridors.append(corridor)
        print(cid, " · ".join(f"{p['name']} {p['km']}" for p in placed))
    with open(OSM / "city-rail-proposal.json", "w") as f:
        f.write("{\"corridors\": [\n" + ",\n".join(json.dumps(c, ensure_ascii=False, separators=(",", ":"))
                                                  for c in corridors) + "\n]}\n")


main()
