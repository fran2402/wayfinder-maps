"""Merges per-shard pack descriptions into index.json and lists stale release files.

Usage: index.py <dir with *.json>  (release file names on stdin)
  writes <dir>/index.json and prints the names of release files no longer used.
"""
import json, os, re, sys

d = sys.argv[1]
metas = []
for f in os.listdir(d):
    if f.endswith(".json") and f != "index.json":
        with open(os.path.join(d, f)) as fh:
            m = json.load(fh)
        m["_file"] = f
        metas.append(m)

def run_of(m):
    try:
        return int(m.get("run", 0))
    except ValueError:
        return 0

index, keep, by_id = {}, set(), {}
for m in metas:
    by_id.setdefault(m["id"], []).append(m)
for rid, ms in by_id.items():
    latest = max(ms, key=run_of)
    shards = latest.get("shards", 1)
    chosen = {}
    # Newest description of each shard of the latest layout (a shard that failed in this
    # run keeps its previous build).
    for m in sorted(ms, key=run_of):
        if m.get("shards", 1) == shards:
            chosen[m.get("shard", 1)] = m
    picked = [chosen[k] for k in sorted(chosen)]
    parts = [p for m in picked for p in m["parts"]]
    index[rid] = {
        "id": rid, "name": latest.get("name", rid), "parts": parts,
        "bytes": sum(m.get("bytes", 0) for m in picked),
        "built": max(m.get("built", "") for m in picked),
        "failed": sum(m.get("failed", 0) for m in picked),
        "shards": shards, "complete": len(picked) == shards,
    }
    keep.update(m["_file"] for m in picked)
    keep.update(parts)

with open(os.path.join(d, "index.json"), "w") as fh:
    json.dump(index, fh, indent=2, sort_keys=True)

pattern = re.compile(r"^(?P<id>[a-z0-9-]+)(\.s\d+)?(\.part\d+)?\.(zip|json)$")
for f in sys.stdin.read().split():
    m = pattern.match(f)
    if m and m.group("id") in index and f not in keep:
        print(f)
