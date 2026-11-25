#!/usr/bin/env python3
"""
Fetches the part of ESA WorldCover 2021 (10 m land cover from satellites; © ESA WorldCover project, contains modified
Copernicus Sentinel data, CC BY 4.0) around Białystok into raw/worldcover.npz: `land` (rows from north to south, the
class codes) and `box` (west, north, degrees per cell). Only the tiles needed are read, with HTTP range requests.

  python3 tools/geo/worldcover.py
"""
import os
import struct
import urllib.request
import zlib

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
URL = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_N51E021_Map.tif"
# South, west, north, east.
BOX = (52.90, 22.80, 53.35, 23.70)


def read(start, length):
    request = urllib.request.Request(URL, headers={"Range": f"bytes={start}-{start + length - 1}",
                                                   "User-Agent": "transit-analytics/1.0"})
    with urllib.request.urlopen(request, timeout=60) as r:
        return r.read()


def main():
    head = read(0, 1 << 16)
    assert head[:4] == b"II*\0", "a little-endian TIFF"
    sizes = {1: "B", 2: "s", 3: "H", 4: "I", 5: "II", 12: "d"}
    at = struct.unpack_from("<I", head, 4)[0]
    tags = {}
    for i in range(struct.unpack_from("<H", head, at)[0]):
        tag, kind, n = struct.unpack_from("<HHI", head, at + 2 + 12 * i)
        if kind not in sizes or kind == 2:
            continue
        fmt = f"<{n * len(sizes[kind])}{sizes[kind][0]}"
        length = struct.calcsize(fmt)
        place = at + 2 + 12 * i + 8
        if length > 4:
            place = struct.unpack_from("<I", head, place)[0]
        data = head[place:place + length] if place + length <= len(head) else read(place, length)
        tags[tag] = struct.unpack(fmt, data)
    width, tw, th = tags[256][0], tags[322][0], tags[323][0]
    assert tags[259][0] in (8, 32946) and tags.get(317, (1,))[0] == 1, "deflate without predictor"
    step, west, north = tags[33550][0], tags[33922][3], tags[33922][4]
    south, w, n, east = BOX
    c0, c1 = int((w - west) / step) // tw, int((east - west) / step) // tw
    r0, r1 = int((north - n) / step) // th, int((north - south) / step) // th
    across = (width + tw - 1) // tw
    rows = []
    for r in range(r0, r1 + 1):
        row = []
        for c in range(c0, c1 + 1):
            i = r * across + c
            row.append(np.frombuffer(zlib.decompress(read(tags[324][i], tags[325][i])), np.uint8).reshape(th, tw))
        rows.append(np.hstack(row))
    land = np.vstack(rows)
    np.savez_compressed(os.path.join(HERE, "raw", "worldcover.npz"), land=land,
                        box=np.array([west + c0 * tw * step, north - r0 * th * step, step]))
    print(land.shape, dict(zip(*[a.tolist() for a in np.unique(land, return_counts=True)])))


if __name__ == "__main__":
    main()
