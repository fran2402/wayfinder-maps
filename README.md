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
- **Public transport and bike route pills** at the top of the map:
  - *Public transport* draws every route in view with its real line colour and line number, from
    [Transitous](https://transitous.org) (`/api/experimental/map/routes`, GTFS data). The server thins the
    network by zoom: long-distance rail when zoomed out, then metro, trams and buses as you zoom in.
  - Mode chips (Trains, Metro, Trams, Buses, Ferries, Other) filter the map. With everything showing,
    tapping a chip focuses on that mode.
  - A row of line pills lists the lines in view, grouped by mode. Tap one to highlight that line.
  - *Bike routes* draws OSM cycle routes (via Overpass, loaded in cached tiles) and cycleways.

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
