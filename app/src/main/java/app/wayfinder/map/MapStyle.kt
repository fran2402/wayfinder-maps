package app.wayfinder.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-tuned MapLibre style for the OpenMapTiles schema served by OpenFreeMap
 * (OpenStreetMap data). Labels use bundled Google Sans Flex Rounded glyphs.
 */
data class MapPalette(
    val dark: Boolean,
    val land: String,
    val residential: String,
    val park: String,
    val wood: String,
    val grass: String,
    val farmland: String,
    val sand: String,
    val ice: String,
    val wetland: String,
    val cemetery: String,
    val hospital: String,
    val school: String,
    val industrial: String,
    val water: String,
    val waterLine: String,
    val waterLabel: String,
    val building: String,
    val buildingLine: String,
    val roadMinor: String,
    val roadMajor: String,
    val roadCasing: String,
    val majorCasing: String,
    val commercial: String,
    val pitch: String,
    val cycleway: String,
    val motorway: String,
    val motorwayCasing: String,
    val path: String,
    val rail: String,
    val boundary: String,
    val label: String,
    val labelMinor: String,
    val halo: String,
    val natureLabel: String,
    val shadow: String,
    val highlight: String,
    // Accents, taken from the Material colour scheme.
    val accent: String,
    val accentContainer: String,
    val destination: String,
    val walk: String,
    val alternative: String,
    val surface: String,
) {
    companion object {
        /** "Soft warm": paper-like land, warm grey buildings, white streets with sand casings. */
        fun light(accent: String, accentContainer: String, destination: String, walk: String) = MapPalette(
            dark = false,
            land = "#F5F3EE", residential = "#EEEBE4", park = "#CDE8C2", wood = "#BBDDAA",
            grass = "#D6ECCB", farmland = "#EFEFE0", sand = "#F3E7C7", ice = "#F3F8FB",
            wetland = "#D3EADD", cemetery = "#D7E4CF", hospital = "#F8DFE2", school = "#F6EBD0",
            industrial = "#ECE9F1", commercial = "#F4E7E4", pitch = "#B8E1A8",
            water = "#A3D0F2", waterLine = "#6FB2E6", waterLabel = "#2D6AA0",
            building = "#E3DCD1", buildingLine = "#D2C9BB", roadMinor = "#FFFFFF", roadMajor = "#FFFFFF",
            roadCasing = "#E7E1D6", majorCasing = "#D8CFC0", motorway = "#FFC57E", motorwayCasing = "#E09A4A",
            path = "#B3A796", cycleway = "#3FA36A",
            rail = "#A39C92", boundary = "#B2A3C6", label = "#35322E", labelMinor = "#6B655D",
            halo = "#FFFFFF", natureLabel = "#3B7445", shadow = "#6B5A47", highlight = "#FFFFFF",
            accent = accent, accentContainer = accentContainer, destination = destination, walk = walk,
            alternative = "#9AA3B2", surface = "#FFFFFF",
        )

        fun dark(accent: String, accentContainer: String, destination: String, walk: String) = MapPalette(
            dark = true,
            land = "#14171B", residential = "#181B20", park = "#17291E", wood = "#1A3022",
            grass = "#1A281F", farmland = "#191D1A", sand = "#29261F", ice = "#1E2227",
            wetland = "#172621", cemetery = "#1A261E", hospital = "#2B1C21", school = "#28251C",
            industrial = "#1B1D24", commercial = "#1F1A21", pitch = "#1E3A28",
            water = "#0D2436", waterLine = "#1D4E73", waterLabel = "#6FA8D8",
            building = "#252A31", buildingLine = "#30363E", roadMinor = "#2C3139", roadMajor = "#3A414B",
            roadCasing = "#101215", majorCasing = "#101215", motorway = "#6B4F27", motorwayCasing = "#3B2C17",
            path = "#4F5661", cycleway = "#4FC07E",
            rail = "#59616C", boundary = "#5E5672", label = "#DDE2E9", labelMinor = "#9BA3AE",
            halo = "#14171B", natureLabel = "#7FBF8C", shadow = "#000000", highlight = "#2A2F36",
            accent = accent, accentContainer = accentContainer, destination = destination, walk = walk,
            alternative = "#5B6472", surface = "#1E2126",
        )
    }
}

object MapStyle {
    const val SRC_ROUTE = "route"
    const val SRC_ROUTE_ALT = "route-alt"
    const val SRC_ROUTE_STOPS = "route-stops"
    const val SRC_MARKERS = "markers"
    const val SRC_PLACE = "place"
    const val SRC_NETWORK = "transit"
    const val SRC_NETWORK_STOPS = "transit-stops"
    const val SRC_BIKE = "bike"

    /** Layers shown only while the public-transport overlay is switched on. */
    val networkLayers = listOf(
        "net-tiles-casing", "net-tiles", "net-lines-casing", "net-lines",
        "net-stops", "net-line-labels", "net-stations", "net-rail-ties", "net-tram-track", "net-ferry", "net-other", "net-stop-labels",
    )

    /** Layers whose opacity follows the highlighted line. */
    val transitLineLayers = listOf("net-lines-casing", "net-lines")

    /** Layers shown only while the bike-route overlay is switched on. */
    val bikeLayers = listOf("bike-tiles-casing", "bike-tiles", "bike-lines-casing", "bike-lines", "bike-labels")

    /** Layers that are dimmed while the network is on, so the lines stand out. */
    val baseRailLayers = listOf("rail", "rail-hatch", "transit-base")

    private const val MEDIUM = "WayfinderMedium"
    private const val BOLD = "WayfinderBold"

    fun build(p: MapPalette, showNetwork: Boolean, showBike: Boolean = false): String {
        val layers = JSONArray()
        fun add(l: JSONObject) { layers.put(l) }

        // ---------- Base ----------
        add(layer("background", "background", paint = obj("background-color" to p.land)))

        add(fill("landuse-residential", "landuse",
            arr("match", get("class"), arr("residential", "suburb", "neighbourhood"), true, false),
            p.residential, opacity = zoomLinear(10 to 0.0, 12 to 1.0)))
        add(fill("landuse-commercial", "landuse",
            arr("match", get("class"), arr("commercial", "retail"), true, false),
            p.commercial, opacity = zoomLinear(11 to 0.0, 13 to 1.0)))
        add(fill("landuse-industrial", "landuse",
            arr("match", get("class"), arr("industrial", "railway", "garages", "quarry"), true, false),
            p.industrial, opacity = zoomLinear(11 to 0.0, 13 to 1.0)))
        add(fill("landuse-hospital", "landuse", eq("class", "hospital"), p.hospital))
        add(fill("landuse-school", "landuse",
            arr("match", get("class"), arr("school", "university", "college", "kindergarten"), true, false), p.school))
        add(fill("landuse-cemetery", "landuse", eq("class", "cemetery"), p.cemetery))

        add(fill("landcover-farmland", "landcover", eq("class", "farmland"), p.farmland, opacity = 0.8))
        add(fill("landcover-wood", "landcover", eq("class", "wood"), p.wood,
            opacity = zoomLinear(5 to 0.5, 12 to 1.0)))
        add(fill("landcover-grass", "landcover", eq("class", "grass"), p.grass, opacity = 0.9))
        add(fill("landcover-park", "landcover",
            arr("all", eq("class", "grass"),
                arr("match", get("subclass"), arr("park", "garden", "recreation_ground", "village_green", "playground"), true, false)),
            p.park))
        add(fill("park", "park", null, p.park, opacity = zoomLinear(4 to 0.35, 9 to 0.6, 12 to 0.8)))
        add(fill("landcover-wetland", "landcover", eq("class", "wetland"), p.wetland, opacity = 0.8))
        add(fill("landcover-sand", "landcover", eq("class", "sand"), p.sand))
        add(fill("landcover-ice", "landcover", eq("class", "ice"), p.ice))
        add(fill("landuse-pitch", "landuse",
            arr("match", get("class"), arr("pitch", "stadium", "playground", "track"), true, false), p.pitch,
            opacity = 0.7))

        add(layer("hillshade", "hillshade", source = "dem", maxzoom = 16.0, paint = obj(
            "hillshade-exaggeration" to zoomLinear(4 to 0.35, 12 to 0.22, 15 to 0.1),
            "hillshade-shadow-color" to if (p.dark) "rgba(0,0,0,0.55)" else "rgba(107,90,71,0.32)",
            "hillshade-highlight-color" to if (p.dark) "rgba(255,255,255,0.03)" else "rgba(255,255,255,0.45)",
            "hillshade-accent-color" to if (p.dark) "rgba(0,0,0,0.3)" else "rgba(107,90,71,0.15)",
        )))

        add(layer("waterway", "line", "waterway",
            filter = arr("!=", get("brunnel"), "tunnel"),
            layout = obj("line-cap" to "round", "line-join" to "round"),
            paint = obj(
                "line-color" to p.waterLine,
                "line-width" to zoomExp(1.4, 8 to 0.6, 12 to 1.2, 16 to 4.0, 20 to 10.0),
            )))
        add(layer("water", "fill", "water",
            filter = arr("!=", get("brunnel"), "tunnel"),
            paint = obj("fill-color" to p.water, "fill-antialias" to true)))
        add(layer("water-shore", "line", "water", minzoom = 12.0,
            filter = arr("!=", get("brunnel"), "tunnel"),
            paint = obj("line-color" to p.waterLine, "line-width" to zoomLinear(12 to 0.5, 18 to 2.0),
                "line-blur" to 1.0)))

        add(layer("aeroway-area", "fill", "aeroway", minzoom = 11.0,
            filter = arr("match", arr("geometry-type"), arr("Polygon", "MultiPolygon"), true, false),
            paint = obj("fill-color" to p.industrial, "fill-opacity" to 0.8)))
        add(layer("aeroway-runway", "line", "aeroway", minzoom = 11.0,
            filter = arr("match", get("class"), arr("runway", "taxiway"), true, false),
            paint = obj("line-color" to p.roadMajor,
                "line-width" to zoomExp(1.5, 11 to 2.0, 17 to 50.0))))

        add(layer("building", "fill", "building", minzoom = 13.0, paint = obj(
            "fill-color" to p.building,
            "fill-outline-color" to p.buildingLine,
            "fill-opacity" to zoomLinear(13 to 0.0, 14.5 to 1.0),
        )))

        // ---------- Roads ----------
        roads(p, "tunnel").forEach(::add)
        roads(p, null).forEach(::add)

        add(layer("transit-base", "line", "transportation", minzoom = 12.0,
            filter = arr("all", eq("class", "transit"), arr("!=", get("brunnel"), "tunnel")),
            paint = obj("line-color" to p.rail, "line-width" to zoomLinear(12 to 0.8, 18 to 2.5))))
        add(layer("rail", "line", "transportation",
            filter = arr("all", eq("class", "rail"), arr("!=", get("brunnel"), "tunnel")),
            paint = obj(
                "line-color" to p.rail,
                "line-width" to zoomExp(1.4, 8 to 0.5, 13 to 1.2, 18 to 3.0),
                "line-opacity" to zoomLinear(7 to 0.4, 10 to 1.0),
            )))
        add(layer("rail-hatch", "line", "transportation", minzoom = 14.0,
            filter = arr("all", eq("class", "rail"), arr("!=", get("brunnel"), "tunnel")),
            paint = obj(
                "line-color" to p.rail,
                "line-width" to zoomLinear(14 to 4.0, 18 to 8.0),
                "line-dasharray" to arr("literal", arr(0.15, 3.0)),
            )))

        roads(p, "bridge").forEach(::add)

        add(layer("boundary-country", "line", "boundary",
            filter = arr("all", arr("==", get("admin_level"), 2), arr("!=", get("maritime"), 1)),
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj("line-color" to p.boundary, "line-width" to zoomLinear(2 to 0.8, 10 to 2.0),
                "line-dasharray" to arr("literal", arr(3, 2)))))
        add(layer("boundary-state", "line", "boundary", minzoom = 4.0,
            filter = arr("all", arr("==", get("admin_level"), 4), arr("!=", get("maritime"), 1)),
            layout = obj("line-join" to "round"),
            paint = obj("line-color" to p.boundary, "line-opacity" to 0.6,
                "line-width" to zoomLinear(4 to 0.5, 10 to 1.2),
                "line-dasharray" to arr("literal", arr(2, 2)))))

        // ---------- Public transport network ----------
        val netVis = if (showNetwork) "visible" else "none"
        val railColor = arr("match", get("subclass"),
            "subway", "#1E63E9",
            "light_rail", "#00A3A3",
            "tram", "#E8473F",
            "monorail", "#00A3A3",
            "funicular", "#B0761E",
            if (p.dark) "#AEB8C7" else "#3D4A5C")
        val bikeVis = if (showBike) "visible" else "none"
        val cyclewayFilter = arr("all", eq("class", "path"), eq("subclass", "cycleway"))
        add(layer("bike-tiles-casing", "line", "transportation", minzoom = 11.0, visibility = bikeVis,
            filter = cyclewayFilter,
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to p.surface,
                "line-width" to zoomExp(1.4, 11 to 2.0, 14 to 4.0, 18 to 9.0),
            )))
        add(layer("bike-tiles", "line", "transportation", minzoom = 11.0, visibility = bikeVis,
            filter = cyclewayFilter,
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to p.cycleway,
                "line-width" to zoomExp(1.4, 11 to 0.8, 14 to 2.0, 18 to 5.0),
            )))
        add(layer("bike-lines-casing", "line", source = SRC_BIKE, visibility = bikeVis,
            layout = obj("line-join" to "round", "line-cap" to "round", "line-sort-key" to get("rank")),
            paint = obj(
                "line-color" to p.surface,
                "line-width" to zoomLinear(9 to 3.0, 13 to 6.5, 18 to 12.0),
            )))
        add(layer("bike-lines", "line", source = SRC_BIKE, visibility = bikeVis,
            layout = obj("line-join" to "round", "line-cap" to "round", "line-sort-key" to get("rank")),
            paint = obj(
                "line-color" to get("color"),
                "line-opacity" to 0.9,
                "line-width" to zoomLinear(9 to 1.6, 13 to 3.6, 18 to 7.0),
            )))

        add(layer("net-tiles-casing", "line", "transportation", visibility = netVis,
            filter = arr("match", get("class"), arr("rail", "transit"), true, false),
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to p.surface,
                "line-width" to zoomExp(1.3, 4 to 1.2, 9 to 2.2, 13 to 3.4, 18 to 6.0),
                "line-opacity" to arr("case", arr("==", get("brunnel"), "tunnel"), 0.5, 0.9),
            )))
        add(layer("net-tiles", "line", "transportation", visibility = netVis,
            filter = arr("match", get("class"), arr("rail", "transit"), true, false),
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to railColor,
                "line-width" to zoomExp(1.3, 4 to 0.5, 9 to 1.0, 13 to 1.6, 18 to 3.0),
                "line-opacity" to arr("case", arr("==", get("brunnel"), "tunnel"), 0.4, 0.7),
            )))
        // Public transport lines: colour from the operator, line style from the mode.
        //   Train: railway (white dashed centre) · Metro: boldest solid · Tram: double track
        //   Bus: thin solid · Ferry: long dashes · Cable car / funicular: dots
        fun byMode(train: Double, metro: Double, tram: Double, bus: Double, other: Double) =
            arr("match", get("mode"), "Train", train, "Metro", metro, "Tram", tram, "Bus", bus, other)
        val lineWidth = arr("interpolate", arr("linear"), arr("zoom"),
            9, byMode(2.4, 2.8, 2.0, 1.1, 1.6),
            13, byMode(5.0, 5.8, 4.2, 2.6, 3.0),
            18, byMode(8.5, 10.0, 7.5, 5.0, 5.0))
        val casingWidth = arr("interpolate", arr("linear"), arr("zoom"),
            9, byMode(4.0, 4.4, 3.4, 2.2, 0.0),
            13, byMode(8.0, 8.8, 7.0, 4.6, 0.0),
            18, byMode(13.0, 14.5, 11.5, 8.0, 0.0))
        val solidModes = arr("match", get("mode"), arr("Ferry", "Other"), false, true)
        add(layer("net-lines-casing", "line", source = SRC_NETWORK, visibility = netVis,
            filter = solidModes,
            layout = obj("line-join" to "round", "line-cap" to "round", "line-sort-key" to get("rank")),
            paint = obj("line-color" to p.surface, "line-width" to casingWidth)))
        add(layer("net-lines", "line", source = SRC_NETWORK, visibility = netVis,
            filter = solidModes,
            layout = obj("line-join" to "round", "line-cap" to "round", "line-sort-key" to get("rank")),
            paint = obj(
                "line-color" to get("color"),
                "line-opacity" to arr("match", get("mode"), "Bus", 0.9, 1.0),
                "line-width" to lineWidth,
            )))
        add(layer("net-rail-ties", "line", source = SRC_NETWORK, visibility = netVis, minzoom = 10.0,
            filter = eq("mode", "Train"),
            layout = obj("line-join" to "round"),
            paint = obj(
                "line-color" to p.surface,
                "line-width" to zoomLinear(10 to 0.8, 13 to 1.4, 18 to 2.2),
                "line-dasharray" to arr("literal", arr(3.0, 3.0)),
            )))
        add(layer("net-tram-track", "line", source = SRC_NETWORK, visibility = netVis, minzoom = 11.0,
            filter = eq("mode", "Tram"),
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj("line-color" to p.surface, "line-width" to zoomLinear(11 to 0.6, 13 to 1.0, 18 to 1.8))))
        add(layer("net-ferry", "line", source = SRC_NETWORK, visibility = netVis,
            filter = eq("mode", "Ferry"),
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to get("color"),
                "line-width" to zoomLinear(9 to 1.6, 13 to 3.0, 18 to 5.0),
                "line-dasharray" to arr("literal", arr(3.0, 2.0)),
            )))
        add(layer("net-other", "line", source = SRC_NETWORK, visibility = netVis,
            filter = eq("mode", "Other"),
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to get("color"),
                "line-width" to zoomLinear(9 to 2.5, 13 to 4.5, 18 to 7.0),
                "line-dasharray" to arr("literal", arr(0.01, 2.0)),
            )))

        // ---------- Route ----------
        add(layer("route-alt", "line", source = SRC_ROUTE_ALT,
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to p.alternative,
                "line-opacity" to 0.75,
                "line-width" to zoomExp(1.4, 8 to 3.0, 14 to 6.0, 18 to 10.0),
            )))
        add(layer("route-casing", "line", source = SRC_ROUTE,
            filter = arr("!=", get("kind"), "walk"),
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to p.surface,
                "line-width" to zoomExp(1.4, 8 to 6.0, 14 to 11.0, 18 to 18.0),
            )))
        add(layer("route-line", "line", source = SRC_ROUTE,
            filter = arr("!=", get("kind"), "walk"),
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to get("color"),
                "line-width" to zoomExp(1.4, 8 to 3.5, 14 to 7.0, 18 to 12.0),
            )))
        add(layer("route-walk", "line", source = SRC_ROUTE,
            filter = eq("kind", "walk"),
            layout = obj("line-join" to "round", "line-cap" to "round"),
            paint = obj(
                "line-color" to get("color"),
                "line-width" to zoomExp(1.4, 8 to 3.0, 14 to 5.5, 18 to 9.0),
                "line-dasharray" to arr("literal", arr(0.01, 1.9)),
            )))

        // ---------- Labels ----------
        add(layer("waterway-label", "symbol", "waterway", minzoom = 13.0,
            filter = arr("has", "name"),
            layout = obj(
                "symbol-placement" to "line",
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to zoomLinear(13 to 11, 18 to 14),
                "text-letter-spacing" to 0.05,
            ),
            paint = textPaint(p.waterLabel, p)))
        add(layer("water-label", "symbol", "water_name",
            filter = arr("match", arr("geometry-type"), arr("Point", "MultiPoint"), true, false),
            layout = obj(
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to arr("match", get("class"), arr("ocean"), 16, arr("sea", "bay", "strait"), 13, 12),
                "text-letter-spacing" to 0.08,
                "text-max-width" to 6,
            ),
            paint = textPaint(p.waterLabel, p)))

        add(layer("road-label", "symbol", "transportation_name", minzoom = 12.5,
            filter = arr("match", get("class"), arr("rail", "transit", "ferry"), false, true),
            layout = obj(
                "symbol-placement" to "line",
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to zoomLinear(13 to 10.5, 16 to 12.5, 19 to 15),
                "text-max-angle" to 30,
                "symbol-spacing" to 350,
            ),
            paint = textPaint(p.labelMinor, p, haloWidth = 1.6)))

        add(layer("housenumber", "symbol", "housenumber", minzoom = 17.0,
            layout = obj(
                "text-field" to get("housenumber"),
                "text-font" to fonts(MEDIUM),
                "text-size" to 10,
            ),
            paint = obj("text-color" to p.labelMinor, "text-opacity" to 0.7,
                "text-halo-color" to p.halo, "text-halo-width" to 1.0)))

        add(layer("peak-label", "symbol", "mountain_peak", minzoom = 11.0,
            filter = arr("has", "name"),
            layout = obj(
                "text-field" to arr("format", nameField(), obj(),
                    arr("case", arr("has", "ele"), arr("concat", "\n", arr("to-string", get("ele")), " m"), ""),
                    obj("font-scale" to 0.8)),
                "text-font" to fonts(MEDIUM),
                "text-size" to 11,
            ),
            paint = textPaint(p.natureLabel, p)))

        add(layer("poi", "symbol", "poi", minzoom = 14.0,
            filter = arr("all",
                arr("has", "name"),
                arr("match", get("class"), arr("railway", "bus", "aerialway", "ferry_terminal"), false, true),
                arr("<=", get("rank"), 25)),
            layout = obj(
                "icon-image" to poiIcon(),
                "icon-size" to zoomLinear(14 to 0.75, 17 to 1.0),
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to 11.5,
                "text-anchor" to "left",
                "text-offset" to arr("literal", arr(1.1, 0.0)),
                "text-max-width" to 8,
                "text-optional" to true,
                "symbol-sort-key" to get("rank"),
            ),
            paint = obj(
                "text-color" to poiColor(p),
                "text-halo-color" to p.halo,
                "text-halo-width" to 1.4,
                "text-halo-blur" to 0.5,
            )))

        add(layer("net-stops", "circle", source = SRC_NETWORK_STOPS, minzoom = 12.0, visibility = netVis,
            paint = obj(
                "circle-color" to p.surface,
                "circle-radius" to arr("interpolate", arr("linear"), arr("zoom"),
                    12, arr("case", arr("<=", get("rank"), 1), 1.5, 2.5),
                    16, arr("case", arr("<=", get("rank"), 1), 3.5, 5.0)),
                "circle-stroke-color" to get("color"),
                "circle-stroke-width" to zoomLinear(12 to 1.2, 16 to 2.2),
            )))
        add(layer("net-line-labels", "symbol", source = SRC_NETWORK, minzoom = 11.0, visibility = netVis,
            filter = arr("has", "pill"),
            layout = obj(
                "symbol-placement" to "line",
                "symbol-spacing" to 320,
                "symbol-sort-key" to arr("-", 10, get("rank")),
                "icon-image" to get("pill"),
                "icon-rotation-alignment" to "viewport",
                "icon-pitch-alignment" to "viewport",
                "icon-padding" to 3,
            )))
        add(layer("bike-labels", "symbol", source = SRC_BIKE, minzoom = 12.0, visibility = bikeVis,
            filter = arr("all", arr("!=", get("ref"), ""), arr("<=", arr("length", get("ref")), 12)),
            layout = obj(
                "symbol-placement" to "line",
                "symbol-spacing" to 360,
                "text-field" to get("ref"),
                "text-font" to fonts(BOLD),
                "text-size" to 11,
                "text-rotation-alignment" to "viewport",
            ),
            paint = obj(
                "text-color" to "#FFFFFF",
                "text-halo-color" to get("color"),
                "text-halo-width" to 2.5,
            )))

        add(layer("net-stop-labels", "symbol", source = SRC_NETWORK_STOPS, minzoom = 11.0, visibility = netVis,
            filter = arr("boolean", false),
            layout = obj(
                "text-field" to get("name"),
                "text-font" to fonts(MEDIUM),
                "text-size" to 12,
                "text-anchor" to "left",
                "text-offset" to arr("literal", arr(0.8, 0.0)),
                "text-max-width" to 9,
                "text-optional" to true,
            ),
            paint = textPaint(p.label, p, haloWidth = 1.8)))

        // Stations: always visible from mid zoom, emphasised when the network is on.
        add(layer("station", "symbol", "poi", minzoom = 12.0,
            filter = arr("all", arr("has", "name"),
                arr("match", get("class"), arr("railway", "bus", "aerialway", "ferry_terminal"), true, false),
                arr("match", get("subclass"), arr("station", "halt", "subway", "tram_stop", "bus_station", "ferry_terminal"), true, false)),
            layout = obj(
                "icon-image" to stationIcon(),
                "icon-size" to zoomLinear(12 to 0.7, 16 to 1.0),
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to 11.5,
                "text-anchor" to "top",
                "text-offset" to arr("literal", arr(0.0, 1.0)),
                "text-max-width" to 8,
                "text-optional" to true,
                "symbol-sort-key" to get("rank"),
            ),
            paint = textPaint(if (p.dark) "#8FB4FF" else "#1F4FC2", p)))
        add(layer("net-stations", "symbol", "poi", minzoom = 9.0, maxzoom = 12.0, visibility = netVis,
            filter = arr("all", arr("has", "name"), eq("class", "railway"),
                arr("match", get("subclass"), arr("station", "subway"), true, false)),
            layout = obj(
                "icon-image" to stationIcon(),
                "icon-size" to 0.65,
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to 10.5,
                "text-anchor" to "top",
                "text-offset" to arr("literal", arr(0.0, 0.9)),
                "text-optional" to true,
                "symbol-sort-key" to get("rank"),
            ),
            paint = textPaint(if (p.dark) "#8FB4FF" else "#1F4FC2", p)))

        add(layer("place-suburb", "symbol", "place", minzoom = 11.0, maxzoom = 16.0,
            filter = arr("match", get("class"), arr("suburb", "quarter", "neighbourhood"), true, false),
            layout = obj(
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to zoomLinear(11 to 10, 15 to 13),
                "text-transform" to "uppercase",
                "text-letter-spacing" to 0.12,
                "text-max-width" to 7,
            ),
            paint = textPaint(p.labelMinor, p)))
        add(layer("place-village", "symbol", "place", minzoom = 10.0,
            filter = arr("match", get("class"), arr("village", "hamlet"), true, false),
            layout = obj(
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to zoomLinear(10 to 10.5, 15 to 14),
                "text-max-width" to 7,
            ),
            paint = textPaint(p.label, p)))
        add(layer("place-town", "symbol", "place", minzoom = 7.0,
            filter = eq("class", "town"),
            layout = obj(
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to zoomLinear(7 to 11, 14 to 17),
                "text-max-width" to 7,
            ),
            paint = textPaint(p.label, p)))
        add(layer("place-city", "symbol", "place", minzoom = 3.5, maxzoom = 15.0,
            filter = eq("class", "city"),
            layout = obj(
                "text-field" to nameField(),
                "text-font" to fonts(BOLD),
                "text-size" to arr("interpolate", arr("linear"), arr("zoom"),
                    4, arr("case", arr("<=", get("rank"), 2), 13, 11),
                    8, arr("case", arr("<=", get("rank"), 2), 18, 15),
                    12, 21),
                "text-max-width" to 8,
                "symbol-sort-key" to get("rank"),
            ),
            paint = textPaint(p.label, p, haloWidth = 2.0)))
        add(layer("place-state", "symbol", "place", minzoom = 4.5, maxzoom = 8.0,
            filter = arr("match", get("class"), arr("state", "province"), true, false),
            layout = obj(
                "text-field" to nameField(),
                "text-font" to fonts(MEDIUM),
                "text-size" to 11,
                "text-transform" to "uppercase",
                "text-letter-spacing" to 0.15,
                "text-max-width" to 8,
            ),
            paint = textPaint(p.labelMinor, p)))
        add(layer("place-country", "symbol", "place", maxzoom = 8.0,
            filter = eq("class", "country"),
            layout = obj(
                "text-field" to nameField(),
                "text-font" to fonts(BOLD),
                "text-size" to zoomLinear(2 to 11, 6 to 16),
                "text-transform" to "uppercase",
                "text-letter-spacing" to 0.12,
                "text-max-width" to 7,
            ),
            paint = textPaint(p.labelMinor, p, haloWidth = 2.0)))

        // ---------- Route markers (on top of everything) ----------
        add(layer("route-stops", "circle", source = SRC_ROUTE_STOPS,
            paint = obj(
                "circle-color" to p.surface,
                "circle-radius" to zoomLinear(8 to 3.5, 16 to 6.5),
                "circle-stroke-color" to get("color"),
                "circle-stroke-width" to zoomLinear(8 to 2.0, 16 to 3.0),
            )))
        add(layer("marker-origin", "circle", source = SRC_MARKERS,
            filter = eq("role", "origin"),
            paint = obj(
                "circle-color" to p.surface,
                "circle-radius" to 8.0,
                "circle-stroke-color" to p.accent,
                "circle-stroke-width" to 4.0,
            )))
        add(layer("marker-stop", "circle", source = SRC_MARKERS,
            filter = eq("role", "stop"),
            paint = obj(
                "circle-color" to p.accent,
                "circle-radius" to 11.0,
                "circle-stroke-color" to p.surface,
                "circle-stroke-width" to 3.0,
            )))
        add(layer("marker-stop-label", "symbol", source = SRC_MARKERS,
            filter = eq("role", "stop"),
            layout = obj(
                "text-field" to get("label"),
                "text-font" to fonts(BOLD),
                "text-size" to 12,
                "text-allow-overlap" to true,
                "text-ignore-placement" to true,
            ),
            paint = obj("text-color" to p.surface)))
        add(layer("marker-place", "symbol", source = SRC_PLACE,
            layout = obj(
                "icon-image" to "pin-destination",
                "icon-anchor" to "bottom",
                "icon-allow-overlap" to true,
                "icon-ignore-placement" to true,
            )))
        add(layer("marker-destination", "symbol", source = SRC_MARKERS,
            filter = eq("role", "destination"),
            layout = obj(
                "icon-image" to "pin-destination",
                "icon-anchor" to "bottom",
                "icon-allow-overlap" to true,
                "icon-ignore-placement" to true,
            )))

        val style = obj(
            "version" to 8,
            "name" to if (p.dark) "Wayfinder Dark" else "Wayfinder Light",
            "glyphs" to "asset://glyphs/{fontstack}/{range}.pbf",
            "sources" to obj(
                "openmaptiles" to obj(
                    "type" to "vector",
                    "url" to "https://tiles.openfreemap.org/planet",
                ),
                "dem" to obj(
                    "type" to "raster-dem",
                    "tiles" to arr("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"),
                    "encoding" to "terrarium",
                    "tileSize" to 256,
                    "maxzoom" to 13,
                    "attribution" to "Terrain: Mapzen, AWS Open Data",
                ),
                SRC_NETWORK to geojson(),
                SRC_NETWORK_STOPS to geojson(),
                SRC_BIKE to geojson(),
                SRC_ROUTE to geojson(),
                SRC_ROUTE_ALT to geojson(),
                SRC_ROUTE_STOPS to geojson(),
                SRC_MARKERS to geojson(),
                SRC_PLACE to geojson(),
            ),
            "layers" to layers,
        )
        return style.toString()
    }

    // ---------- Roads ----------

    private class RoadClass(
        val id: String,
        val classes: List<String>,
        val minzoom: Double,
        val width: Array<Pair<Number, Number>>,
        val fill: String,
        val casing: String,
        val casingExtra: Double,
    )

    private fun roadClasses(p: MapPalette) = listOf(
        RoadClass("path", listOf("path", "track"), 14.0,
            arrayOf(14 to 0.8, 18 to 2.6), p.path, p.path, 0.0),
        RoadClass("service", listOf("service"), 14.0,
            arrayOf(14 to 1.2, 18 to 8.0), p.roadMinor, p.roadCasing, 1.0),
        RoadClass("minor", listOf("minor"), 12.0,
            arrayOf(12 to 0.6, 14 to 2.6, 18 to 16.0), p.roadMinor, p.roadCasing, 1.2),
        RoadClass("secondary", listOf("secondary", "tertiary"), 9.0,
            arrayOf(9 to 0.6, 12 to 1.6, 14 to 4.5, 18 to 20.0), p.roadMajor, p.majorCasing, 1.5),
        RoadClass("primary", listOf("primary", "trunk"), 6.0,
            arrayOf(6 to 0.5, 10 to 1.6, 14 to 6.0, 18 to 24.0), p.roadMajor, p.majorCasing, 1.8),
        RoadClass("motorway", listOf("motorway"), 5.0,
            arrayOf(5 to 0.7, 10 to 2.2, 14 to 7.0, 18 to 28.0), p.motorway, p.motorwayCasing, 2.0),
    )

    private fun roads(p: MapPalette, brunnel: String?): List<JSONObject> {
        val suffix = brunnel ?: "road"
        val brunnelFilter = when (brunnel) {
            null -> arr("match", get("brunnel"), arr("tunnel", "bridge"), false, true)
            else -> eq("brunnel", brunnel)
        }
        val opacity: Any = if (brunnel == "tunnel") 0.55 else 1.0
        val casings = ArrayList<JSONObject>()
        val fills = ArrayList<JSONObject>()
        for (rc in roadClasses(p)) {
            val filter = arr("all", brunnelFilter, arr("match", get("class"), JSONArray(rc.classes), true, false))
            val fillWidth = zoomExp(1.5, *rc.width)
            if (rc.id == "path") {
                fills += layer("$suffix-${rc.id}", "line", "transportation", minzoom = rc.minzoom, filter = filter,
                    layout = obj("line-join" to "round"),
                    paint = obj(
                        "line-color" to rc.fill,
                        "line-width" to fillWidth,
                        "line-opacity" to opacity,
                        "line-dasharray" to arr("literal", arr(2.0, 1.4)),
                    ))
                continue
            }
            val casingWidth = zoomExp(1.5, *rc.width.map { (z, w) ->
                z to (w.toDouble() + if (z.toDouble() < 12) rc.casingExtra * 0.5 else rc.casingExtra * 1.6)
            }.toTypedArray())
            casings += layer("$suffix-${rc.id}-casing", "line", "transportation",
                minzoom = maxOf(rc.minzoom, if (rc.id == "minor" || rc.id == "service") 13.0 else 7.0),
                filter = filter,
                layout = obj("line-join" to "round", "line-cap" to if (brunnel == "bridge") "butt" else "round"),
                paint = obj(
                    "line-color" to rc.casing,
                    "line-width" to casingWidth,
                    "line-opacity" to opacity,
                    "line-dasharray" to if (brunnel == "tunnel") arr("literal", arr(1.0, 0.6)) else null,
                ))
            fills += layer("$suffix-${rc.id}", "line", "transportation", minzoom = rc.minzoom, filter = filter,
                layout = obj("line-join" to "round", "line-cap" to "round"),
                paint = obj(
                    "line-color" to rc.fill,
                    "line-width" to fillWidth,
                    "line-opacity" to opacity,
                ))
        }
        return casings + fills
    }

    // ---------- POI styling ----------

    private val poiGroups = linkedMapOf(
        "food" to listOf("restaurant", "fast_food", "cafe", "bar", "beer", "ice_cream", "bakery", "food_court", "pub"),
        "shop" to listOf("shop", "grocery", "clothing_store", "supermarket", "convenience", "mall", "department_store",
            "alcohol_shop", "bag", "books", "furniture", "hairdresser", "jewelry", "laundry", "mobile_phone", "shoe"),
        "nature" to listOf("park", "garden", "campsite", "playground", "zoo", "golf", "swimming", "sports", "stadium",
            "pitch", "dog_park", "picnic_site"),
        "health" to listOf("hospital", "pharmacy", "doctors", "dentist", "veterinary", "clinic"),
        "culture" to listOf("museum", "art_gallery", "theatre", "cinema", "attraction", "castle", "monument",
            "library", "music", "information", "viewpoint"),
        "worship" to listOf("place_of_worship"),
        "education" to listOf("school", "college", "university", "kindergarten"),
        "lodging" to listOf("hotel", "lodging", "motel", "hostel", "guest_house"),
        "money" to listOf("bank", "atm", "post", "town_hall", "police", "fire_station"),
        "fuel" to listOf("fuel", "charging_station", "car", "parking", "bicycle"),
    )

    val poiColorsLight = mapOf(
        "food" to "#E26A00", "shop" to "#2F6FDB", "nature" to "#2E8B4F", "health" to "#D33A4B",
        "culture" to "#0F8C8C", "worship" to "#6E6A64", "education" to "#8C6A10", "lodging" to "#C2338A",
        "money" to "#5B6B82", "fuel" to "#5B6B82", "default" to "#7A746B",
    )
    val poiColorsDark = mapOf(
        "food" to "#FF9E57", "shop" to "#7FB0FF", "nature" to "#6CCB8B", "health" to "#FF8090",
        "culture" to "#5ED0D0", "worship" to "#B3ADA5", "education" to "#E2BF5C", "lodging" to "#F08ACB",
        "money" to "#A7B4C8", "fuel" to "#A7B4C8", "default" to "#B3ADA5",
    )

    /** POI group ("food", "shop", …) for an OpenMapTiles poi class. */
    fun poiGroupOf(poiClass: String?): String =
        poiGroups.entries.firstOrNull { poiClass != null && poiClass in it.value }?.key ?: "default"

    private fun poiGroupExpr(mapTo: (String) -> Any): JSONArray {
        val e = arr("match", get("class"))
        poiGroups.forEach { (group, classes) -> e.put(JSONArray(classes)); e.put(mapTo(group)) }
        e.put(mapTo("default"))
        return e
    }

    private fun poiIcon() = poiGroupExpr { "poi-$it" }
    private fun poiColor(p: MapPalette): JSONArray {
        val colors = if (p.dark) poiColorsDark else poiColorsLight
        return poiGroupExpr { colors.getValue(it) }
    }

    private fun stationIcon() = arr("match", get("subclass"),
        "subway", "station-subway",
        "tram_stop", "station-tram",
        "bus_station", "station-bus",
        "ferry_terminal", "station-ferry",
        arr("match", get("class"), "bus", "station-bus", "ferry_terminal", "station-ferry", "station-train"))

    // ---------- JSON helpers ----------

    private fun nameField() = arr("coalesce", get("name"), get("name_en"), get("name:latin"))
    private fun fonts(vararg f: String) = arr("literal", JSONArray(f.toList()))

    private fun textPaint(color: String, p: MapPalette, haloWidth: Double = 1.4) = obj(
        "text-color" to color,
        "text-halo-color" to p.halo,
        "text-halo-width" to haloWidth,
        "text-halo-blur" to 0.5,
    )

    private fun geojson() = obj(
        "type" to "geojson",
        "data" to obj("type" to "FeatureCollection", "features" to JSONArray()),
    )

    private fun fill(id: String, sourceLayer: String, filter: JSONArray?, color: String, opacity: Any = 1.0) =
        layer(id, "fill", sourceLayer, filter = filter, paint = obj("fill-color" to color, "fill-opacity" to opacity))

    private fun layer(
        id: String,
        type: String,
        sourceLayer: String? = null,
        source: String? = if (sourceLayer != null) "openmaptiles" else null,
        filter: JSONArray? = null,
        minzoom: Double? = null,
        maxzoom: Double? = null,
        visibility: String? = null,
        layout: JSONObject? = null,
        paint: JSONObject? = null,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        source?.let { put("source", it) }
        sourceLayer?.let { put("source-layer", it) }
        filter?.let { put("filter", it) }
        minzoom?.let { put("minzoom", it) }
        maxzoom?.let { put("maxzoom", it) }
        val l = layout ?: JSONObject()
        visibility?.let { l.put("visibility", it) }
        if (l.length() > 0) put("layout", l)
        paint?.let { put("paint", it) }
    }

    private fun get(key: String) = arr("get", key)
    private fun eq(key: String, value: Any) = arr("==", get(key), value)

    private fun zoomLinear(vararg stops: Pair<Number, Number>) = interpolate(arr("linear"), stops)
    private fun zoomExp(base: Double, vararg stops: Pair<Number, Number>) = interpolate(arr("exponential", base), stops)
    private fun interpolate(curve: JSONArray, stops: Array<out Pair<Number, Number>>): JSONArray {
        val e = arr("interpolate", curve, arr("zoom"))
        stops.forEach { (z, v) -> e.put(z.toDouble()); e.put(v.toDouble()) }
        return e
    }

    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        pairs.forEach { (k, v) -> if (v != null) put(k, v) }
    }

    private fun arr(vararg values: Any?) = JSONArray().apply { values.forEach { put(it) } }
}
