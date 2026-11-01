package app.wayfinder.map

import android.annotation.SuppressLint
import android.content.Context
import app.wayfinder.data.Bounds
import app.wayfinder.data.Itinerary
import app.wayfinder.data.LatLon
import app.wayfinder.data.LegMode
import app.wayfinder.data.NetworkData
import app.wayfinder.data.TransitNetwork
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
import org.maplibre.android.style.layers.PropertyFactory
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
    private var network: NetworkData? = null
    private var networkVisible = false
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

    fun applyStyle(palette: MapPalette, showNetwork: Boolean, onLoaded: () -> Unit = {}) {
        val map = map ?: return
        this.palette = palette
        networkVisible = showNetwork
        val key = palette.toString()
        if (key == styleKey) {
            setNetworkVisible(showNetwork)
            return
        }
        styleKey = key
        style = null
        map.setStyle(Style.Builder().fromJson(MapStyle.build(palette, networkVisible))) { loaded ->
            style = loaded
            MapIcons.install(context, loaded, palette)
            setNetworkVisible(networkVisible)
            route?.let { drawRoute(it) }
            network?.let { drawNetwork(it) }
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
        return Bounds(b.latSouth, b.lonWest, b.latNorth, b.lonEast)
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

    // ---------- Network ----------

    fun setNetwork(data: NetworkData?) {
        network = data
        drawNetwork(data)
    }

    private fun drawNetwork(data: NetworkData?) {
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_NETWORK)?.setGeoJson(data?.lines ?: TransitNetwork.EMPTY)
        s.getSourceAs<GeoJsonSource>(MapStyle.SRC_NETWORK_STOPS)?.setGeoJson(data?.stops ?: TransitNetwork.EMPTY)
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
