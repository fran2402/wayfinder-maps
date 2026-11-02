"""Turns an OpenStreetMap extract holding bike route relations into Overpass-style JSON.

Usage: bikes.py <bikes.osm.pbf> <out.json>
Input should already be filtered (osmium tags-filter r/route=bicycle,mtb), so it is small.
Output: {"elements": [relations with members and tags..., ways with geometry...]}, the same
shape Overpass answers with, which the app's BikeRoutes parser reads.
"""
import json, sys
import osmium

src, dst = sys.argv[1], sys.argv[2]


class Relations(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.rels = []
        self.ways = set()

    def relation(self, r):
        if r.tags.get("type") != "route" or r.tags.get("route") not in ("bicycle", "mtb"):
            return
        members = []
        for m in r.members:
            if m.type == "w":
                members.append({"type": "way", "ref": m.ref, "role": m.role})
                self.ways.add(m.ref)
        self.rels.append({"type": "relation", "id": r.id, "members": members, "tags": {t.k: t.v for t in r.tags}})


class Ways(osmium.SimpleHandler):
    def __init__(self, wanted, out):
        super().__init__()
        self.wanted = wanted
        self.out = out
        self.first = True

    def way(self, w):
        if w.id not in self.wanted:
            return
        geom = []
        for n in w.nodes:
            if n.location.valid():
                geom.append({"lat": round(n.location.lat, 7), "lon": round(n.location.lon, 7)})
        if len(geom) >= 2:
            self.out.write(",")
            json.dump({"type": "way", "id": w.id, "geometry": geom}, self.out, separators=(",", ":"))


rels = Relations()
rels.apply_file(src)
with open(dst, "w") as out:
    out.write('{"elements":[')
    out.write(",".join(json.dumps(r, separators=(",", ":")) for r in rels.rels) or "")
    if not rels.rels:
        out.write('{"type":"none"}')
    Ways(rels.ways, out).apply_file(src, locations=True)
    out.write("]}")
print(f"{len(rels.rels)} bike routes, {len(rels.ways)} ways")
