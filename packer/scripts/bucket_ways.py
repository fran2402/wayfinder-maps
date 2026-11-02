"""Splits OSM roads and tracks (osmium export geojsonseq) into one file per download square.

Usage: bucket_ways.py <ways.geojsonseq> <outdir>
Each output line is "<kind> lat lon lat lon ...", kind: r road, t tram, l light rail,
s subway, m monorail, R rail. A way goes into every square (zoom-8 tile) it touches.
"""
import json, math, os, sys

src, outdir = sys.argv[1], sys.argv[2]
os.makedirs(outdir, exist_ok=True)
RAIL = {"tram": "t", "light_rail": "l", "subway": "s", "monorail": "m", "rail": "R", "narrow_gauge": "R"}


def tile(lat, lon, z=8):
    n = 1 << z
    x = int((lon + 180.0) / 360.0 * n)
    lr = math.radians(max(-85.0, min(85.0, lat)))
    y = int((1.0 - math.log(math.tan(lr) + 1.0 / math.cos(lr)) / math.pi) / 2.0 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


files = {}
def out(key):
    f = files.get(key)
    if f is None:
        if len(files) > 400:  # keep open files bounded
            for v in files.values():
                v.close()
            files.clear()
        f = open(os.path.join(outdir, f"{key[0]}_{key[1]}.txt"), "a")
        files[key] = f
    return f


count = 0
with open(src) as fh:
    for line in fh:
        line = line.strip().lstrip("\x1e")
        if not line:
            continue
        feat = json.loads(line)
        g = feat.get("geometry") or {}
        if g.get("type") != "LineString":
            continue
        p = feat.get("properties") or {}
        kind = RAIL.get(p.get("railway")) or ("r" if p.get("highway") else None)
        if kind is None:
            continue
        coords = g["coordinates"]
        text = kind + " " + " ".join(f"{c[1]:.6f} {c[0]:.6f}" for c in coords) + "\n"
        keys = {tile(c[1], c[0]) for c in coords}
        for k in keys:
            out(k).write(text)
        count += 1
for v in files.values():
    v.close()
print(f"{count} roads and tracks bucketed")
