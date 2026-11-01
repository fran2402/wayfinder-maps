# Wayfinder

A clean, uncluttered Android maps app for getting around by **public transport, train, on foot and by bike**.
There is no bottom navigation bar: the map fills the screen and everything else floats on top of it.

- **Material 3 Expressive**: `MaterialExpressiveTheme` with the expressive motion scheme, a connected
  toggle-button group for travel modes, the expressive loading indicators, and shape-morphing FABs
  (`MaterialShapes`).
- **Google Sans Flex, rounded**: the UI uses Google Sans Flex with the `ROND` axis at 100. Map labels use
  the same font, through glyph files generated from it (`tools/glyphs`), with Noto Sans as a fallback for Greek and Cyrillic.
- **Phosphor icons** everywhere, including the POI and station badges drawn on the map.
- **OpenStreetMap** vector tiles from [OpenFreeMap](https://openfreemap.org), drawn with a custom
  light and dark style (`MapStyle.kt`): soft land colours, hillshading, coloured POI badges and roads
  with casings.
- **Whole transit network on demand**: tap the network button (or the *Network* chip in the route sheet) to
  draw every line in view in its official colour, with stops. The rail network comes from the vector tiles at
  every zoom level. Individual lines (train, metro, tram, ferry, plus buses when zoomed in) are loaded from OSM
  route relations via the Overpass API. In Train mode the network is limited to rail.

## Routing

| Mode | Service |
| --- | --- |
| Public transport | [Transitous](https://transitous.org) (MOTIS), all transit modes |
| Train | Transitous, rail-only (`RAIL`, `SUBURBAN`) |
| Walking | [FOSSGIS OSRM](https://routing.openstreetmap.de) foot profile |
| Cycling | FOSSGIS OSRM bike profile |

Place search uses [Photon](https://photon.komoot.io). Long-press the map to drop a pin.

## Building

The project builds on GitHub Actions (`.github/workflows/build.yml`). Each push uploads a
`wayfinder-apk` artifact containing debug and release APKs (the release APK is signed with the debug key so it installs directly).

Locally: `./gradlew assembleDebug` (JDK 17, Android SDK 36).

## Credits

Map data © OpenStreetMap contributors · Tiles by OpenFreeMap · Terrain: Mapzen terrain tiles on AWS Open Data ·
Google Sans Flex and Noto Sans under the SIL Open Font License · Phosphor Icons (MIT).
