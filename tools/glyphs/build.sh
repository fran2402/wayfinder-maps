#!/usr/bin/env bash
# Regenerates the map label glyphs in app/src/main/assets/glyphs.
# Needs: python3 + fonttools, node + playwright (with Chromium).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p fonts
GF=https://raw.githubusercontent.com/google/fonts/main/ofl
curl -sSfo fonts/gsf.ttf "$GF/googlesansflex/GoogleSansFlex%5BROND,opsz,slnt,wdth,wght%5D.ttf"
curl -sSfo fonts/noto.ttf "$GF/notosans/NotoSans%5Bwdth,wght%5D.ttf"
inst() { python3 -m fontTools.varLib.instancer "$@" -q; }
inst fonts/gsf.ttf ROND=100 slnt=0 wdth=100 opsz=14 wght=520 -o fonts/map_medium.ttf
inst fonts/gsf.ttf ROND=100 slnt=0 wdth=100 opsz=14 wght=720 -o fonts/map_bold.ttf
inst fonts/noto.ttf wdth=100 wght=500 -o fonts/noto_medium.ttf
inst fonts/noto.ttf wdth=100 wght=700 -o fonts/noto_bold.ttf
# The rounded UI font (weight axis kept variable).
inst fonts/gsf.ttf ROND=100 slnt=0 wdth=100 -o ../../app/src/main/res/font/google_sans_flex_rounded.ttf
python3 - <<'PY'
from fontTools.ttLib import TTFont
import json
cps = set()
for f in ["fonts/map_medium.ttf", "fonts/noto_medium.ttf"]:
    cps |= {c for c in TTFont(f).getBestCmap() if c < 0x3000 or 0xFB00 <= c < 0xFC00}
json.dump(sorted(cps - set(range(32))), open("cps.json", "w"))
PY
OUT=../../app/src/main/assets/glyphs
rm -rf "$OUT" && mkdir -p "$OUT"
node gen.js "$OUT"
node empty.js "$OUT"
echo "Glyphs written to $OUT"
