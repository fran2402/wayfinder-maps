"""Turns an OpenStreetMap extract of route relations into Overpass-style JSON files.

Usage: osm_routes.py <routes.osm.pbf> <bikes.json> <transit.json>
Input should already be filtered (osmium tags-filter r/route=...), so it is small.
bikes.json   bicycle and mtb routes;
transit.json train, subway, light_rail, monorail, tram, bus and trolleybus routes (used to lay
             Transitous lines exactly on the tracks and roads drawn on the map).
Each is {"elements": [relations with members and tags..., ways with geometry...]}.
"""
import json, sys
import osmium

src, bikes_out, transit_out = sys.argv[1], sys.argv[2], sys.argv[3]
BIKE = {"bicycle", "mtb"}
TRANSIT = {"train", "subway", "light_rail", "monorail", "tram", "bus", "trolleybus"}


class Relations(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.rels = {"bike": [], "transit": []}
        self.ways = {"bike": set(), "transit": set()}

    def relation(self, r):
        route = r.tags.get("route")
        if r.tags.get("type") != "route":
            return
        kind = "bike" if route in BIKE else "transit" if route in TRANSIT else None
        if kind is None:
            return
        members = []
        for m in r.members:
            # Platforms and stop positions are not the path.
            if m.type == "w" and not m.role.startswith("platform"):
                members.append({"type": "way", "ref": m.ref, "role": m.role})
                self.ways[kind].add(m.ref)
        if members:
            self.rels[kind].append({"type": "relation", "id": r.id, "members": members, "tags": {t.k: t.v for t in r.tags}})


class Ways(osmium.SimpleHandler):
    def __init__(self, wanted):
        super().__init__()
        self.wanted = wanted
        self.geom = {}

    def way(self, w):
        if w.id not in self.wanted:
            return
        g = [{"lat": round(n.location.lat, 7), "lon": round(n.location.lon, 7)} for n in w.nodes if n.location.valid()]
        if len(g) >= 2:
            self.geom[w.id] = g


rels = Relations()
rels.apply_file(src)
ways = Ways(rels.ways["bike"] | rels.ways["transit"])
ways.apply_file(src, locations=True)

for kind, path in (("bike", bikes_out), ("transit", transit_out)):
    with open(path, "w") as out:
        out.write('{"elements":[')
        first = True
        for r in rels.rels[kind]:
            out.write(("" if first else ",") + json.dumps(r, separators=(",", ":")))
            first = False
        for wid in rels.ways[kind]:
            g = ways.geom.get(wid)
            if g:
                out.write(("" if first else ",") + json.dumps({"type": "way", "id": wid, "geometry": g}, separators=(",", ":")))
                first = False
        out.write("]}")
    print(f"{kind}: {len(rels.rels[kind])} routes, {len(rels.ways[kind])} ways")
