package app.wayfinder.map

import android.annotation.SuppressLint
import android.content.Context
import app.wayfinder.data.Bounds
import app.wayfinder.data.Itinerary
import app.wayfinder.data.LatLon
import app.wayfinder.data.LegMode
import app.wayfinder.data.Overlays
import app.wayfinder.data.TransitMode
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** Route currently drawn on the map, already resolved to colours. */
data class RouteDrawing(
    val itineraries: List<Itinerary>,
    val selected: Int,
    val origin: LatLon?,
    val destination: LatLon?,
    /** Colour used for walking/cycling-only routes and walk legs inside transit trips. */
    val accent: String,
    val walk: String,
)

/**
 * Thin imperative wrapper around [MapLibreMap]. It remembers the last data it was given so
 * everything is re-applied after a style reload (e.g. switching between light and dark).
 */
class MapController(private val context: Context) {
    var map: MapLibreMap? = null
        private set
    private var style: Style? = null
    private var styleKey: String? = null

    private var route: RouteDrawing? = null
    private var transitLines: String = Overlays.EMPTY
    private var transitStops: String = Overlays.EMPTY
    private var bikeLines: String = Overlays.EMPTY
    private var networkVisible = false
    private var bikeVisible = false
    private var highlightedLine: String? = null
    private var transitModes: Set<TransitMode> = TransitMode.entries.toSet()
    private var locationEnabled = false

    fun attach(map: MapLibreMap) {
        this.map = map
        map.uiSettings.apply {
            isCompassEnabled = true
            isLogoEnabled = false
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = false
            setAttributionTintColor(0x99888888.toInt())
        }
    }

    fun applyStyle(palette: MapPalette, onLoaded: () -> Unit = {}) {
        val map = map ?: return
        this.palette = palette
        val key = palette.toString()
        if (key == styleKey) return
        styleKey = key
        style = null
        map.setStyle(Style.Builder().fromJson(MapStyle.build(palette, networkVisible, bikeVisible))) { loaded ->
            style = loaded
            MapIcons.install(context, loaded, palette)
            setNetworkVisible(networkVisible)
            setBikeVisible(bikeVisible)
            route?.let { drawRoute(it) }
            drawOverlays()
            setTransitModes(transitModes)
            highlightLine(highlightedLine)
            if (locationEnabled) activateLocation(loaded, palette)
            onLoaded()
        }
    }

    private var palette: MapPalette? = null

    // ---------- Location ----------

    @SuppressLint("MissingPermission")
    fun enableLocation() {
        locationEnabled = true
        val s = style ?: return
        activateLocation(s, palette ?: return)
    }

    @SuppressLint("MissingPermission")
    private fun activateLocation(style: Style, palette: MapPalette) {
        val map = map ?: return
        val accent = android.graphics.Color.parseColor(palette.accent)
        val options = LocationComponentOptions.builder(context)
            .foregroundTintColor(accent)
            .backgroundTintColor(android.graphics.Color.WHITE)
            .bearingTintColor(accent)
            .accuracyColor(accent)
            .accuracyAlpha(0.12f)
            .pulseEnabled(true)
            .pulseColor(accent)
            .elevation(4f)
            .build()
        runCatching {
            map.locationComponent.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .locationComponentOptions(options)
                    .useDefaultLocationEngine(true)
                    .build()
            )
            map.locationComponent.isLocationComponentEnabled = true
            map.locationComponent.renderMode = RenderMode.COMPASS
            map.locationComponent.cameraMode = CameraMode.NONE
        }
    }

    // ---------- Camera ----------

    fun visibleBounds(): Bounds? {
        val b = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return Bounds(b.getLatSouth(), b.getLonWest(), b.getLatNorth(), b.getLonEast())
    }

    fun zoom(): Double = map?.cameraPosition?.zoom ?: 0.0

    fun center(): LatLon? = map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }

    fun flyTo(pos: LatLon, zoom: Double = 15.0) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.lat, pos.lon), zoom), 900)
    }

    fun moveTo(pos: LatLon, zoom: Double) {
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.lat, pos.lon), zoom))
    }

    fun fit(points: List<LatLon>, left: Int, top: Int, right: Int, bottom: Int) {
        val map = map ?: return
        if (points.isEmpty()) return
        if (points.size == 1 || points.all { it == points.first() }) {
            flyTo(points.first(), 15.0); return
        }
        val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
        runCatching {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, left, top, right, bottom), 900)
        }
    }

    // ---------- Overlays ----------

    fun setTransit(lines: String, stops: String) {
        transitLines = lines
        transitStops = stops
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_NETWORK)?.setGeoJson(lines)
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_NETWORK_STOPS)?.setGeoJson(stops)
    }

    fun setBike(lines: String) {
        bikeLines = lines
        style?.getSourceAs<GeoJsonSource>(MapStyle.SRC_BIKE)?.setGeoJson(lines)
    }

    private fun drawOverlays() {
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_NETWORK)?.setGeoJson(transitLines)
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_NETWORK_STOPS)?.setGeoJson(transitStops)
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_BIKE)?.setGeoJson(bikeLines)
    }

    fun setNetworkVisible(show: Boolean) {
        networkVisible = show
        val s = style ?: return
        val v = if (show) Property.VISIBLE else Property.NONE
        MapStyle.networkLayers.forEach { s.getLayer(it)?.setProperties(PropertyFactory.visibility(v)) }
        MapStyle.baseRailLayers.forEach {
            s.getLayer(it)?.setProperties(PropertyFactory.lineOpacity(if (show) 0.25f else 1f))
        }
    }

    fun setBikeVisible(show: Boolean) {
        bikeVisible = show
        val s = style ?: return
        val v = if (show) Property.VISIBLE else Property.NONE
        MapStyle.bikeLayers.forEach { s.getLayer(it)?.setProperties(PropertyFactory.visibility(v)) }
    }

    /** Shows only the chosen kinds of public transport, both our lines and the base map's rail. */
    fun setTransitModes(modes: Set<TransitMode>) {
        transitModes = modes
        val s = style ?: return
        val names = JSONArray(modes.map { it.name })
        val none = "[\"==\",1,0]"
        val byMode = if (modes.isEmpty()) none else "[\"match\",[\"get\",\"mode\"],$names,true,false]"
        listOf("net-lines-casing", "net-lines", "net-stops").forEach { s.getLayer(it)?.filter(byMode) }
        s.getLayer("net-line-labels")?.filter("[\"all\",[\"!=\",[\"get\",\"ref\"],\"\"],$byMode]")

        val subclasses = JSONArray(modes.flatMap {
            when (it) {
                TransitMode.Train -> listOf("rail", "narrow_gauge", "preserved")
                TransitMode.Metro -> listOf("subway", "light_rail", "monorail")
                TransitMode.Tram -> listOf("tram")
                TransitMode.Other -> listOf("funicular")
                TransitMode.Bus, TransitMode.Ferry -> emptyList()
            }
        })
        val tiles = if (subclasses.length() == 0) none else
            "[\"all\",[\"match\",[\"get\",\"class\"],[\"rail\",\"transit\"],true,false]," +
                "[\"match\",[\"get\",\"subclass\"],$subclasses,true,false]]"
        listOf("net-tiles-casing", "net-tiles").forEach { s.getLayer(it)?.filter(tiles) }
    }

    private fun Layer.filter(json: String) {
        val e = Expression.Converter.convert(json)
        when (this) {
            is LineLayer -> setFilter(e)
            is CircleLayer -> setFilter(e)
            is SymbolLayer -> setFilter(e)
        }
    }

    /** Fades every transit line except [key]; null shows all lines again. */
    fun highlightLine(key: String?) {
        highlightedLine = key
        val s = style ?: return
        val match = Expression.eq(Expression.get("key"), Expression.literal(key ?: ""))
        val lineOpacity = if (key == null) Expression.literal(1f)
        else Expression.switchCase(match, Expression.literal(1f), Expression.literal(0.12f))
        MapStyle.transitLineLayers.forEach { s.getLayer(it)?.setProperties(PropertyFactory.lineOpacity(lineOpacity)) }
        val labelOpacity = if (key == null) Expression.literal(1f)
        else Expression.switchCase(match, Expression.literal(1f), Expression.literal(0f))
        s.getLayer("net-line-labels")?.setProperties(
            PropertyFactory.iconOpacity(labelOpacity),
            PropertyFactory.textOpacity(labelOpacity),
        )
        s.getLayer("net-stops")?.setProperties(
            PropertyFactory.circleOpacity(labelOpacity),
            PropertyFactory.circleStrokeOpacity(labelOpacity),
        )
    }

    // ---------- Route ----------

    fun setRoute(drawing: RouteDrawing?) {
        route = drawing
        drawRoute(drawing)
    }

    private fun drawRoute(d: RouteDrawing?) {
        val s = style ?: return
        val routeFeatures = JSONArray()
        val altFeatures = JSONArray()
        val stopFeatures = JSONArray()
        val markers = JSONArray()

        if (d != null) {
            d.itineraries.forEachIndexed { index, itinerary ->
                if (index == d.selected) return@forEachIndexed
                val geom = itinerary.geometry
                if (geom.size >= 2) altFeatures.put(line(geom, JSONObject()))
            }
            d.itineraries.getOrNull(d.selected)?.let { itinerary ->
                val walkOnly = itinerary.legs.none { it.mode.isTransit }
                for (leg in itinerary.legs) {
                    if (leg.geometry.size < 2) continue
                    val (kind, color) = when {
                        leg.mode == LegMode.Walk -> "walk" to (if (walkOnly) d.accent else d.walk)
                        leg.mode == LegMode.Bike -> "bike" to d.accent
                        else -> "transit" to hex(leg.color ?: defaultLegColor(leg.mode))
                    }
                    routeFeatures.put(line(leg.geometry, JSONObject().put("kind", kind).put("color", color)))
                    if (leg.mode.isTransit) {
                        stopFeatures.put(point(leg.geometry.first(), JSONObject().put("color", color)))
                        stopFeatures.put(point(leg.geometry.last(), JSONObject().put("color", color)))
                    }
                }
            }
            d.origin?.let { markers.put(point(it, JSONObject().put("role", "origin"))) }
            d.destination?.let { markers.put(point(it, JSONObject().put("role", "destination"))) }
        }

        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE)?.setGeoJson(collection(routeFeatures))
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE_ALT)?.setGeoJson(collection(altFeatures))
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_ROUTE_STOPS)?.setGeoJson(collection(stopFeatures))
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_MARKERS)?.setGeoJson(collection(markers))
    }

    private fun line(points: List<LatLon>, props: JSONObject): JSONObject {
        val coords = JSONArray()
        points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
        return JSONObject().put("type", "Feature").put("properties", props)
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
    }

    private fun point(p: LatLon, props: JSONObject) = JSONObject().put("type", "Feature").put("properties", props)
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))

    private fun collection(features: JSONArray) =
        JSONObject().put("type", "FeatureCollection").put("features", features).toString()

    companion object {
        fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

        fun defaultLegColor(mode: LegMode): Int = when (mode) {
            LegMode.Bus -> 0xFF7E57C2
            LegMode.Tram -> 0xFFE8473F
            LegMode.Subway -> 0xFF1E63E9
            LegMode.Rail -> 0xFF3D4A5C
            LegMode.Ferry -> 0xFF0091D5
            LegMode.Air -> 0xFF5B6B82
            LegMode.Bike -> 0xFF2E9E4F
            LegMode.Walk, LegMode.Other -> 0xFF6B7280
        }.toInt()
    }
}
