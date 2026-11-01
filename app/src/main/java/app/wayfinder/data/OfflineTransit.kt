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
        val presets = listOf(
            Region("europe", "Europe", Bounds(34.5, -11.0, 71.5, 41.0), listOf(Level.Rail, Level.City)),
            Region("north-america", "North America", Bounds(24.0, -125.0, 50.0, -66.0), listOf(Level.Rail, Level.City)),
        )

        /** Small areas also get buses. */
        fun visibleArea(view: Bounds) = Region(
            id = "area-" + System.currentTimeMillis(),
            name = "Area around " + String.format(java.util.Locale.US, "%.2f, %.2f", (view.south + view.north) / 2, (view.west + view.east) / 2),
            area = view,
            levels = listOf(Level.Rail, Level.City, Level.Street),
        )

        private const val VERSION = 1
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
