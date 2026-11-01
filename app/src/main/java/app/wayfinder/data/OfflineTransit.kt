package app.wayfinder.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Downloaded transit data, so lines draw from disk instead of the network.
 *
 * Tiles are stored per detail level: [Level.Rail] for continent views, [Level.City] for
 * country and city views (rail, metro, tram), and [Level.Street] with buses for small areas.
 */
class OfflineTransit(context: Context) {

    /** A storage level: tile zoom, the detail asked of the server, and whether buses are kept. */
    enum class Level(val z: Int, val serverZoom: Int, val includeBus: Boolean) {
        Rail(6, 9, false),
        City(8, 12, false),
        Street(11, 14, true),
    }

    /** A downloadable area. */
    data class Region(val id: String, val name: String, val area: Bounds, val levels: List<Level>)

    /** A region on disk. */
    data class Saved(val region: Region, val tiles: Int, val bytes: Long, val complete: Boolean)

    private val dir = File(context.filesDir, "transit").apply { mkdirs() }
    private val index = File(dir, "regions.json")

    companion object {
        private val wide = listOf(Level.Rail, Level.City)
        private val all = listOf(Level.Rail, Level.City, Level.Street)

        /** Whole continents: trains, metro and trams. */
        val continents = listOf(
            Region("europe", "Europe", Bounds(34.5, -11.0, 71.5, 41.0), wide),
            Region("north-america", "North America", Bounds(24.0, -125.0, 50.0, -66.0), wide),
            Region("japan", "Japan", Bounds(30.9, 129.3, 45.6, 146.0), wide),
            Region("australia-nz", "Australia and New Zealand", Bounds(-47.5, 112.5, -10.5, 179.0), wide),
        )

        /** Countries and areas: smaller and quicker to download. */
        val countries = listOf(
            Region("uk-ireland", "UK and Ireland", Bounds(49.8, -10.8, 59.0, 2.0), wide),
            Region("france", "France", Bounds(41.3, -5.3, 51.2, 9.7), wide),
            Region("benelux", "Belgium, Netherlands, Luxembourg", Bounds(49.4, 2.5, 53.6, 7.3), wide),
            Region("germany", "Germany", Bounds(47.2, 5.8, 55.1, 15.1), wide),
            Region("alps", "Switzerland and Austria", Bounds(45.8, 5.9, 49.1, 17.2), wide),
            Region("italy", "Italy", Bounds(36.6, 6.6, 47.1, 18.6), wide),
            Region("iberia", "Spain and Portugal", Bounds(35.9, -9.6, 43.9, 3.4), wide),
            Region("nordics", "Nordic countries", Bounds(54.5, 4.5, 71.2, 31.6), wide),
            Region("central-europe", "Poland, Czechia, Slovakia, Hungary", Bounds(45.7, 12.0, 55.0, 24.2), wide),
            Region("balkans", "Croatia, Slovenia and the Balkans", Bounds(39.6, 13.3, 46.9, 23.1), wide),
            Region("greece", "Greece", Bounds(34.8, 19.3, 41.8, 28.3), wide),
            Region("us-northeast", "US Northeast", Bounds(38.0, -80.6, 45.1, -69.8), wide),
            Region("california", "California", Bounds(32.5, -124.5, 42.0, -114.1), wide),
        )

        /** Big cities, with every bus line. */
        val cities = listOf(
            Region("london", "London", Bounds(51.28, -0.52, 51.70, 0.34), all),
            Region("paris", "Paris", Bounds(48.70, 2.10, 49.02, 2.60), all),
            Region("berlin", "Berlin", Bounds(52.33, 13.08, 52.68, 13.77), all),
            Region("vienna", "Vienna", Bounds(48.11, 16.18, 48.33, 16.58), all),
            Region("zagreb", "Zagreb", Bounds(45.72, 15.84, 45.88, 16.15), all),
            Region("amsterdam", "Amsterdam", Bounds(52.27, 4.73, 52.43, 5.07), all),
            Region("madrid", "Madrid", Bounds(40.31, -3.84, 40.56, -3.52), all),
            Region("new-york", "New York", Bounds(40.48, -74.27, 40.93, -73.68), all),
            Region("tokyo", "Tokyo", Bounds(35.50, 139.40, 35.85, 139.95), all),
        )

        val presets = continents + countries + cities

        /** Small areas also get buses. */
        fun visibleArea(view: Bounds) = Region(
            id = "area-" + System.currentTimeMillis(),
            name = "Area around " + String.format(java.util.Locale.US, "%.2f, %.2f", (view.south + view.north) / 2, (view.west + view.east) / 2),
            area = view,
            levels = listOf(Level.Rail, Level.City, Level.Street),
        )

        private const val VERSION = 1

        /** A square may be quartered this many times over (4 levels = 256 pieces). */
        private const val MAX_SPLITS = 4
    }

    // ---------- Tiles ----------

    fun tilesOf(region: Region): List<Triple<Level, Int, Int>> = region.levels.flatMap { level ->
        val x0 = Tiles.lonToX(region.area.west, level.z); val x1 = Tiles.lonToX(region.area.east, level.z)
        val y0 = Tiles.latToY(region.area.north, level.z); val y1 = Tiles.latToY(region.area.south, level.z)
        (x0..x1).flatMap { x -> (y0..y1).map { y -> Triple(level, x, y) } }
    }

    private fun file(level: Level, x: Int, y: Int) = File(dir, "${level.z}_${x}_$y.bin")

    /** Names of tile files on disk, so lookups never touch the file system. */
    private val present: MutableSet<String> = java.util.Collections.synchronizedSet(
        HashSet(dir.list()?.filter { it.endsWith(".bin") }.orEmpty())
    )

    fun has(level: Level, x: Int, y: Int) = "${level.z}_${x}_$y.bin" in present

    fun levelForZ(z: Int): Level? = Level.entries.firstOrNull { it.z == z }

    suspend fun read(level: Level, x: Int, y: Int): TileData? = withContext(Dispatchers.IO) {
        val f = file(level, x, y)
        if (!f.exists()) return@withContext null
        runCatching {
            DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream()), 1 shl 16)).use { decode(it) }
        }.getOrNull()
    }

    suspend fun write(level: Level, x: Int, y: Int, data: TileData) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "${level.z}_${x}_$y.tmp")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()), 1 shl 16)).use { encode(data, it) }
        tmp.renameTo(file(level, x, y))
        present += "${level.z}_${x}_$y.bin"
    }

    // ---------- Fetching ----------

    /**
     * Downloads one tile. Busy or unreachable servers are waited out; any other failure
     * (usually a timeout on a dense area) splits the square into quarters, up to twice, and
     * joins the parts. [onProblem] gets a short reason each time something goes wrong.
     */
    suspend fun fetch(level: Level, x: Int, y: Int, onProblem: (String) -> Unit): TileData =
        fetchArea(Tiles.bounds(level.z, x, y), level, level.serverZoom, 0, onProblem)

    private suspend fun fetchArea(area: Bounds, level: Level, zoom: Int, depth: Int, onProblem: (String) -> Unit): TileData {
        var waits = 0
        var tries = 0
        while (true) {
            try {
                return TransitRoutes.loadTile(area, zoom, level.includeBus)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onProblem(describe(e))
                val busy = e is HttpException && (e.code == 429 || e.code == 503)
                val offline = e is java.net.UnknownHostException || e is java.net.ConnectException
                // 422 is Transitous saying the area holds more routes than one answer may carry.
                val tooDense = e is HttpException && e.code == 422
                val timedOut = e is java.io.InterruptedIOException
                if ((busy || offline) && waits < 8) {
                    waits++
                    val hint = (e as? HttpException)?.retryAfterS?.coerceIn(1, 300)?.times(1000)
                    kotlinx.coroutines.delay(hint ?: (10_000L * waits).coerceAtMost(60_000L))
                    continue
                }
                if ((tooDense || timedOut || tries > 0) && depth < MAX_SPLITS) {
                    return TransitRoutes.combine(quarters(area).map { q ->
                        fetchArea(q, level, zoom, depth + 1, onProblem).also { kotlinx.coroutines.delay(150) }
                    })
                }
                if (tooDense && zoom > 6) {
                    // Still too dense when small: ask for less detail (the server then leaves out minor routes).
                    return fetchArea(area, level, zoom - 1, depth, onProblem)
                }
                tries++
                if (tries >= 3) throw e
                kotlinx.coroutines.delay(4_000L * tries)
            }
        }
    }

    private fun quarters(area: Bounds): List<Bounds> {
        val midLat = (area.south + area.north) / 2
        val midLon = (area.west + area.east) / 2
        return listOf(
            Bounds(midLat, area.west, area.north, midLon), Bounds(midLat, midLon, area.north, area.east),
            Bounds(area.south, area.west, midLat, midLon), Bounds(area.south, midLon, midLat, area.east),
        )
    }

    private fun describe(e: Exception): String = when {
        e is HttpException && (e.code == 429 || e.code == 503) -> "server busy, waiting"
        e is HttpException && e.code == 422 -> "area too dense, splitting it up"
        e is HttpException -> "server error ${e.code}"
        e is java.io.InterruptedIOException -> "timed out, splitting it up"
        e is java.net.UnknownHostException || e is java.net.ConnectException -> "no connection, waiting"
        else -> e.message?.take(60) ?: e.javaClass.simpleName
    }

    // ---------- Regions ----------

    fun saved(): List<Saved> = runCatching {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONObject("area")
            Saved(
                Region(
                    o.getString("id"), o.getString("name"),
                    Bounds(b.getDouble("s"), b.getDouble("w"), b.getDouble("n"), b.getDouble("e")),
                    o.getJSONArray("levels").let { l -> (0 until l.length()).map { Level.valueOf(l.getString(it)) } },
                ),
                o.optInt("tiles"), o.optLong("bytes"), o.optBoolean("complete"),
            )
        }
    }.getOrDefault(emptyList())

    fun record(saved: Saved) {
        val list = saved().filter { it.region.id != saved.region.id } + saved
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().put("id", s.region.id).put("name", s.region.name)
                    .put("area", JSONObject().put("s", s.region.area.south).put("w", s.region.area.west)
                        .put("n", s.region.area.north).put("e", s.region.area.east))
                    .put("levels", JSONArray(s.region.levels.map { it.name }))
                    .put("tiles", s.tiles).put("bytes", s.bytes).put("complete", s.complete)
            )
        }
        index.writeText(arr.toString())
    }

    /** Removes a region; tiles shared with another saved region are kept. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val all = saved()
        val target = all.firstOrNull { it.region.id == id } ?: return@withContext
        val keep = all.filter { it.region.id != id }.flatMap { tilesOf(it.region) }.toHashSet()
        tilesOf(target.region).filter { it !in keep }.forEach { (l, x, y) ->
            file(l, x, y).delete()
            present -= "${l.z}_${x}_$y.bin"
        }
        val arr = JSONArray()
        index.writeText(arr.toString())
        all.filter { it.region.id != id }.forEach { record(it) }
    }

    fun bytesOf(region: Region): Long = tilesOf(region).sumOf { (l, x, y) -> file(l, x, y).length() }

    // ---------- Encoding ----------

    private fun encode(t: TileData, out: DataOutputStream) {
        out.writeInt(VERSION)
        out.writeBoolean(t.zoomFiltered)
        out.writeInt(t.routes.size)
        for (r in t.routes) {
            out.writeUTF(r.key); out.writeUTF(r.mode.name); out.writeUTF(r.osmRoute)
            out.writeUTF(r.ref); out.writeUTF(r.name.take(200))
            out.writeInt(r.color); out.writeInt(r.text); out.writeInt(r.rank)
            out.writeDouble(r.bounds.south); out.writeDouble(r.bounds.west)
            out.writeDouble(r.bounds.north); out.writeDouble(r.bounds.east)
            out.writeInt(r.parts.size)
            r.parts.forEachIndexed { i, p ->
                out.writeInt(r.partIds[i])
                out.writeInt(p.size)
                p.forEach { out.writeFloat(it) }
            }
            out.writeInt(r.stops.size)
            r.stops.forEach { s -> out.writeUTF(s.id); out.writeUTF(s.name.take(200)); out.writeDouble(s.lat); out.writeDouble(s.lon) }
        }
    }

    private fun decode(inp: DataInputStream): TileData {
        require(inp.readInt() == VERSION)
        val zoomFiltered = inp.readBoolean()
        val n = inp.readInt()
        val routes = ArrayList<TileRoute>(n)
        repeat(n) {
            val key = inp.readUTF(); val mode = TransitMode.valueOf(inp.readUTF()); val osmRoute = inp.readUTF()
            val ref = inp.readUTF(); val name = inp.readUTF()
            val color = inp.readInt(); val text = inp.readInt(); val rank = inp.readInt()
            val bounds = Bounds(inp.readDouble(), inp.readDouble(), inp.readDouble(), inp.readDouble())
            val pc = inp.readInt()
            val parts = ArrayList<FloatArray>(pc)
            val ids = IntArray(pc)
            for (i in 0 until pc) {
                ids[i] = inp.readInt()
                val len = inp.readInt()
                parts += FloatArray(len) { inp.readFloat() }
            }
            val sc = inp.readInt()
            val stops = List(sc) { StopRef(inp.readUTF(), inp.readUTF(), inp.readDouble(), inp.readDouble()) }
            routes += TileRoute(key, mode, osmRoute, ref, name, color, text, rank, parts, ids, stops, bounds)
        }
        return TileData(routes, zoomFiltered)
    }
}
