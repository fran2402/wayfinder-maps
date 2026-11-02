package app.wayfinder.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
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
 * Downloaded transit data, every mode including buses, so lines draw from disk at any zoom.
 *
 * A region is fetched once, at full detail, in squares of [Level.City] size ("units"). Each unit
 * is stored as [Level.Street] tiles (full detail) plus one [Level.City] tile simplified for
 * zoomed-out views; [Level.Rail] tiles for continent views are then built on the phone from
 * the City tiles. A unit is finished once its City tile is on disk.
 */
class OfflineTransit(context: Context) {

    /** A storage level: tile zoom, and the zoom its shapes are simplified for. */
    enum class Level(val z: Int, val detailZoom: Int, val maxRoutes: Int) {
        Rail(6, 9, 1_500),
        City(8, 12, 4_000),
        Street(10, 14, Int.MAX_VALUE),
    }

    /** A downloadable area. */
    data class Region(val id: String, val name: String, val area: Bounds)

    /** A region on disk. */
    data class Saved(val region: Region, val tiles: Int, val bytes: Long, val complete: Boolean)

    private val dir = File(context.filesDir, "transit").apply { mkdirs() }
    private val index = File(dir, "regions.json")

    init {
        // Older data lacks coaches and some trains: start afresh.
        val marker = File(dir, "format")
        if (runCatching { marker.readText().trim() }.getOrNull() != VERSION.toString()) {
            dir.listFiles()?.forEach { it.delete() }
            marker.writeText(VERSION.toString())
        }
    }

    companion object {
        /** Whole continents. */
        val continents = listOf(
            Region("europe", "Europe", Bounds(34.5, -11.0, 71.5, 41.0)),
            Region("north-america", "North America", Bounds(24.0, -125.0, 50.0, -66.0)),
            Region("japan", "Japan", Bounds(30.9, 129.3, 45.6, 146.0)),
            Region("australia-nz", "Australia and New Zealand", Bounds(-47.5, 112.5, -10.5, 179.0)),
        )

        /** Countries and areas: smaller and quicker to download. */
        val countries = listOf(
            Region("uk-ireland", "UK and Ireland", Bounds(49.8, -10.8, 59.0, 2.0)),
            Region("france", "France", Bounds(41.3, -5.3, 51.2, 9.7)),
            Region("benelux", "Belgium, Netherlands, Luxembourg", Bounds(49.4, 2.5, 53.6, 7.3)),
            Region("germany", "Germany", Bounds(47.2, 5.8, 55.1, 15.1)),
            Region("alps", "Switzerland and Austria", Bounds(45.8, 5.9, 49.1, 17.2)),
            Region("italy", "Italy", Bounds(36.6, 6.6, 47.1, 18.6)),
            Region("iberia", "Spain and Portugal", Bounds(35.9, -9.6, 43.9, 3.4)),
            Region("nordics", "Nordic countries", Bounds(54.5, 4.5, 71.2, 31.6)),
            Region("central-europe", "Poland, Czechia, Slovakia, Hungary", Bounds(45.7, 12.0, 55.0, 24.2)),
            Region("balkans", "Croatia, Slovenia and the Balkans", Bounds(39.6, 13.3, 46.9, 23.1)),
            Region("greece", "Greece", Bounds(34.8, 19.3, 41.8, 28.3)),
            Region("us-northeast", "US Northeast", Bounds(38.0, -80.6, 45.1, -69.8)),
            Region("california", "California", Bounds(32.5, -124.5, 42.0, -114.1)),
        )

        /** Big cities (with the country around them). */
        val cities = listOf(
            Region("london", "London", Bounds(51.28, -0.52, 51.70, 0.34)),
            Region("paris", "Paris", Bounds(48.70, 2.10, 49.02, 2.60)),
            Region("berlin", "Berlin", Bounds(52.33, 13.08, 52.68, 13.77)),
            Region("vienna", "Vienna", Bounds(48.11, 16.18, 48.33, 16.58)),
            Region("zagreb", "Zagreb", Bounds(45.72, 15.84, 45.88, 16.15)),
            Region("amsterdam", "Amsterdam", Bounds(52.27, 4.73, 52.43, 5.07)),
            Region("madrid", "Madrid", Bounds(40.31, -3.84, 40.56, -3.52)),
            Region("new-york", "New York", Bounds(40.48, -74.27, 40.93, -73.68)),
            Region("tokyo", "Tokyo", Bounds(35.50, 139.40, 35.85, 139.95)),
        )

        val presets = continents + countries + cities

        fun visibleArea(view: Bounds) = Region(
            id = "area-" + System.currentTimeMillis(),
            name = "Area around " + String.format(java.util.Locale.US, "%.2f, %.2f", (view.south + view.north) / 2, (view.west + view.east) / 2),
            area = view,
        )

        private val VERSION = TileCodec.VERSION
        private val PACK_NAME = Regex("^(bike_)?\\d{1,2}_\\d{1,7}_\\d{1,7}\\.bin$")

        /** Server zoom for downloads: Transitous includes every mode from zoom 11 up. */
        private const val SERVER_ZOOM = 14

        /** A unit may be quartered this many times over (6 levels, down to about 2 km). */
        private const val MAX_SPLITS = 6

        /** Requests in flight at once, across the whole download. */
        private const val PARALLEL = 24

        /** Every square starts split into at least 4^this pieces. */
        private const val MIN_START_DEPTH = 2
    }

    // ---------- Tiles ----------

    private fun range(area: Bounds, z: Int): Pair<IntRange, IntRange> =
        (Tiles.lonToX(area.west, z)..Tiles.lonToX(area.east, z)) to (Tiles.latToY(area.north, z)..Tiles.latToY(area.south, z))

    /** The download squares of a region (City tiles). */
    fun unitsOf(region: Region): List<Pair<Int, Int>> {
        val (xs, ys) = range(region.area, Level.City.z)
        return xs.flatMap { x -> ys.map { y -> x to y } }
    }

    /** Every tile a region stores, at every level. */
    fun tilesOf(region: Region): List<Triple<Level, Int, Int>> {
        val out = ArrayList<Triple<Level, Int, Int>>()
        val (rx, ry) = range(region.area, Level.Rail.z)
        for (x in rx) for (y in ry) out += Triple(Level.Rail, x, y)
        val k = 1 shl (Level.Street.z - Level.City.z)
        for ((x, y) in unitsOf(region)) {
            out += Triple(Level.City, x, y)
            for (i in 0 until k) for (j in 0 until k) out += Triple(Level.Street, x * k + i, y * k + j)
        }
        return out
    }

    private fun name(level: Level, x: Int, y: Int) = "${level.z}_${x}_$y.bin"
    private fun file(level: Level, x: Int, y: Int) = File(dir, name(level, x, y))

    /** Names of tile files on disk, so lookups never touch the file system. */
    private val present: MutableSet<String> = java.util.Collections.synchronizedSet(
        HashSet(dir.list()?.filter { it.endsWith(".bin") }.orEmpty())
    )

    fun has(level: Level, x: Int, y: Int) = name(level, x, y) in present

    fun levelForZ(z: Int): Level? = Level.entries.firstOrNull { it.z == z }

    suspend fun read(level: Level, x: Int, y: Int): TileData? = withContext(Dispatchers.IO) {
        val f = file(level, x, y)
        if (!f.exists()) return@withContext null
        runCatching {
            val t = readCodec(f)
            // Drops stop-to-stop jumps, including in data saved before that was filtered at download.
            TransitRoutes.clean(t, toleranceMetres(level.detailZoom))
        }.getOrNull()
    }

    suspend fun write(level: Level, x: Int, y: Int, data: TileData) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "${level.z}_${x}_$y.tmp")
        writeCodec(tmp, data)
        tmp.renameTo(file(level, x, y))
        present += name(level, x, y)
    }

    private fun toleranceMetres(zoom: Int) = 40_075_000.0 / (256.0 * Math.pow(2.0, zoom.toDouble())) / 3.0

    // ---------- Bike routes ----------

    private fun bikeName(x: Int, y: Int) = "bike_${Level.City.z}_${x}_$y.bin"
    private fun bikeFile(x: Int, y: Int) = File(dir, bikeName(x, y))

    fun hasBike(x: Int, y: Int) = bikeName(x, y) in present

    /** Saved bike routes of a download square, or null if not downloaded. */
    suspend fun readBike(x: Int, y: Int): TileData? = withContext(Dispatchers.IO) {
        if (!hasBike(x, y)) return@withContext null
        runCatching { readCodec(bikeFile(x, y)) }.getOrNull()
    }

    private val bikeGate = Semaphore(2) // Overpass allows few parallel queries per client

    /** Stores bike routes for a square that came from elsewhere (the pack builder's OSM extract). */
    suspend fun writeBike(x: Int, y: Int, data: TileData) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "${bikeName(x, y)}.tmp")
        writeCodec(tmp, data)
        tmp.renameTo(bikeFile(x, y))
        present += bikeName(x, y)
    }

    /** Downloads the bike routes of one square, splitting it when Overpass gives up on it. */
    suspend fun downloadBike(x: Int, y: Int, force: Boolean = false, onProblem: (String) -> Unit) {
        if (!force && hasBike(x, y)) return
        val builder = TransitRoutes.TileBuilder()
        suspend fun get(area: Bounds, depth: Int) {
            var tries = 0
            while (true) {
                val res = bikeGate.withPermit { runCatching { BikeRoutes.fetch(area, large = true) } }
                val e = res.exceptionOrNull()
                if (e == null) { synchronized(builder) { builder.addAll(res.getOrThrow()) }; return }
                if (e is CancellationException) throw e
                onProblem("bike routes: " + (e.message?.take(40) ?: "failed"))
                if (depth < 3) {
                    coroutineScope { quarters(area).map { q -> async { get(q, depth + 1) } }.awaitAll() }
                    return
                }
                tries++
                if (tries >= 3) throw e
                delay(5_000L * tries)
            }
        }
        get(Tiles.bounds(Level.City.z, x, y), 0)
        withContext(Dispatchers.IO) {
            val tmp = File(dir, "${bikeName(x, y)}.tmp")
            writeCodec(tmp, builder.build())
            tmp.renameTo(bikeFile(x, y))
            present += bikeName(x, y)
        }
    }

    // ---------- Downloading ----------

    private val gate = Semaphore(PARALLEL)

    /**
     * Adaptive concurrency: requests in flight are capped at [allowed], which grows by one
     * after every few successes and halves when the server says it is busy. That finds the
     * fastest rate Transitous accepts instead of stalling everything for long pauses.
     */
    @Volatile private var allowed = 6
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val okStreak = java.util.concurrent.atomic.AtomicInteger(0)

    /** Short shared pause after the server said it is busy. */
    @Volatile private var pauseUntil = 0L

    private suspend fun <T> limited(block: suspend () -> T): T = gate.withPermit {
        while (true) {
            val wait = pauseUntil - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val n = inFlight.get()
            if (n < allowed && inFlight.compareAndSet(n, n + 1)) break
            delay(40)
        }
        try { block() } finally { inFlight.decrementAndGet() }
    }

    private fun onServerOk() {
        if (okStreak.incrementAndGet() >= 4) { okStreak.set(0); if (allowed < PARALLEL) allowed++ }
    }

    private fun onServerBusy(retryAfterS: Long?) {
        okStreak.set(0)
        allowed = maxOf(2, allowed / 2)
        val jitter = (Math.random() * 800).toLong()
        pauseUntil = maxOf(pauseUntil, System.currentTimeMillis() + (retryAfterS?.coerceIn(1, 30)?.times(1000) ?: 1_500L) + jitter)
    }

    /**
     * How finely the last unit had to be split. Neighbouring squares are similarly dense, so
     * the next unit starts split this far and skips the "too dense" round trips.
     */
    @Volatile private var learnedDepth = 0

    /**
     * Every square starts split into at least 4^this pieces. Small pieces answer quickly on a
     * phone; the pack builder uses 0 (fewer, bigger requests where the server limits the rate).
     */
    var minStartDepth = MIN_START_DEPTH

    /** Applied to each street tile before it is saved (the pack builder lays lines onto OSM geometry). */
    var align: ((TileData, Bounds) -> TileData)? = null

    /** True once a download square's lines are on disk. */
    fun hasLines(x: Int, y: Int) = has(Level.City, x, y)

    /** The download square containing a tile of any level at or below City size. */
    fun unitOf(z: Int, x: Int, y: Int): Pair<Int, Int> {
        val shift = z - Level.City.z
        return if (shift >= 0) (x shr shift) to (y shr shift) else (x shl -shift) to (y shl -shift)
    }

    /**
     * Downloads one unit at full detail: its Street tiles, then its City tile. Requests for the
     * parts of a unit run in parallel (at most [PARALLEL] across the whole download).
     */
    suspend fun downloadUnit(x: Int, y: Int, force: Boolean = false, onProblem: (String) -> Unit) {
        if (!force && has(Level.City, x, y)) return
        val unit = Tiles.bounds(Level.City.z, x, y)
        val k = 1 shl (Level.Street.z - Level.City.z)
        val childBounds = List(k * k) { Tiles.bounds(Level.Street.z, x * k + it % k, y * k + it / k) }
        val builders = List(k * k) { TransitRoutes.TileBuilder() }
        var coarsest = Int.MAX_VALUE

        // Each answered piece is cut into the Street tiles it overlaps, then dropped.
        val sink: suspend (Bounds, Int, TileData) -> Unit = { area, depth, data ->
            withContext(Dispatchers.Default) {
                synchronized(builders) {
                    coarsest = minOf(coarsest, depth)
                    for (i in childBounds.indices) {
                        val cb = childBounds[i]
                        if (!cb.intersects(area)) continue
                        for (r in data.routes) TransitRoutes.clip(r, cb)?.let(builders[i]::add)
                    }
                }
            }
        }
        // Start small (about 40 km pieces, or smaller where neighbours needed it): small requests
        // answer quickly and rarely come back "too dense", so no time is lost splitting.
        val start = learnedDepth.coerceIn(minStartDepth, 4)
        coroutineScope {
            split(unit, start).map { piece -> async { fetchArea(piece, start, onProblem, sink) } }.awaitAll()
        }
        learnedDepth = if (coarsest == Int.MAX_VALUE) 0 else coarsest

        val city = TransitRoutes.TileBuilder()
        for (i in childBounds.indices) {
            val t = builders[i].build().let { b -> align?.invoke(b, childBounds[i]) ?: b }
            write(Level.Street, x * k + i % k, y * k + i / k, t)
            city.addAll(withContext(Dispatchers.Default) { TransitRoutes.reduce(t, Level.City.detailZoom, Int.MAX_VALUE) })
        }
        val cityTile = withContext(Dispatchers.Default) {
            TransitRoutes.reduce(city.build(), Level.City.detailZoom, Level.City.maxRoutes)
        }
        write(Level.City, x, y, cityTile) // last: marks the unit done
    }

    private val railLock = kotlinx.coroutines.sync.Mutex()

    /** Rebuilds one continent-view tile from the City tiles under it (no network). */
    suspend fun rebuildRail(x: Int, y: Int) = railLock.withLock {
        withContext(Dispatchers.Default) {
            val k = 1 shl (Level.City.z - Level.Rail.z)
            val b = TransitRoutes.TileBuilder()
            var any = false
            for (i in 0 until k) for (j in 0 until k) {
                val t = read(Level.City, x * k + i, y * k + j) ?: continue
                any = true
                b.addAll(TransitRoutes.reduce(t, Level.Rail.detailZoom, Int.MAX_VALUE))
            }
            if (any) write(Level.Rail, x, y, TransitRoutes.reduce(b.build(), Level.Rail.detailZoom, Level.Rail.maxRoutes))
        }
    }

    /** Builds the continent-view tiles of a region from its City tiles. */
    suspend fun buildOverview(region: Region) {
        val (rx, ry) = range(region.area, Level.Rail.z)
        for (x in rx) for (y in ry) rebuildRail(x, y)
    }

    private fun split(area: Bounds, depth: Int): List<Bounds> {
        var pieces = listOf(area)
        repeat(depth) { pieces = pieces.flatMap(::quarters) }
        return pieces
    }

    private suspend fun fetchArea(
        area: Bounds,
        depth: Int,
        onProblem: (String) -> Unit,
        sink: suspend (Bounds, Int, TileData) -> Unit,
    ) {
        var waits = 0
        var tries = 0
        var zoom = SERVER_ZOOM
        while (true) {
            val result = limited {
                runCatching { TransitRoutes.loadTile(area, zoom, includeBus = true, maxRoutes = Int.MAX_VALUE) }
            }
            val e = result.exceptionOrNull()
            if (e == null) { onServerOk(); sink(area, depth, result.getOrThrow()); return }
            if (e is CancellationException) throw e
            onProblem(describe(e))
            val busy = e is HttpException && (e.code == 429 || e.code == 503)
            val offline = e is java.net.UnknownHostException || e is java.net.ConnectException
            // 422 is Transitous saying the area holds more routes than one answer may carry.
            val tooDense = e is HttpException && e.code == 422
            val timedOut = e is java.io.InterruptedIOException
            if (busy && waits < 12) {
                waits++
                onServerBusy((e as? HttpException)?.retryAfterS)
                continue
            }
            if (offline && waits < 8) {
                waits++
                delay(5_000L * waits)
                continue
            }
            if ((tooDense || timedOut || tries > 0) && depth < MAX_SPLITS) {
                // Very dense: jump two levels at once (16 pieces) to save round trips.
                val step = if (tooDense && depth + 2 <= MAX_SPLITS) 2 else 1
                coroutineScope {
                    split(area, step).map { q -> async { fetchArea(q, depth + step, onProblem, sink) } }.awaitAll()
                }
                return
            }
            if (tooDense && zoom > 11) { zoom--; continue }
            tries++
            if (tries >= 3) throw e
            delay(4_000L * tries)
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

    private fun describe(e: Throwable): String = when {
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
                Region(o.getString("id"), o.getString("name"), Bounds(b.getDouble("s"), b.getDouble("w"), b.getDouble("n"), b.getDouble("e"))),
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
            present -= name(l, x, y)
            if (l == Level.City) { bikeFile(x, y).delete(); present -= bikeName(x, y) }
        }
        index.writeText(JSONArray().toString())
        all.filter { it.region.id != id }.forEach { record(it) }
    }

    suspend fun bytesOf(region: Region): Long = withContext(Dispatchers.IO) {
        tilesOf(region).sumOf { (l, x, y) ->
            (if (has(l, x, y)) file(l, x, y).length() else 0L) +
                (if (l == Level.City && hasBike(x, y)) bikeFile(x, y).length() else 0L)
        }
    }

    // ---------- Storage ----------

    private fun regionUnits(): Set<Pair<Int, Int>> = saved().flatMap { unitsOf(it.region) }.toHashSet()

    private fun unitBytes(x: Int, y: Int): Long {
        val k = 1 shl (Level.Street.z - Level.City.z)
        var sum = file(Level.City, x, y).length() + bikeFile(x, y).length()
        for (i in 0 until k) for (j in 0 until k) sum += file(Level.Street, x * k + i, y * k + j).length()
        return sum
    }

    private fun browsingUnits(): List<Pair<Int, Int>> {
        val inRegions = regionUnits()
        val prefix = "${Level.City.z}_"
        return present.toList().filter { it.startsWith(prefix) && it.endsWith(".bin") }.mapNotNull { n ->
            val p = n.removeSuffix(".bin").split('_')
            if (p.size == 3) p[1].toIntOrNull()?.let { x -> p[2].toIntOrNull()?.let { y -> x to y } } else null
        }.filter { it !in inRegions }
    }

    /** Squares saved while browsing (not part of a saved region): how many and their size. */
    suspend fun browsingStats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val units = browsingUnits()
        units.size to units.sumOf { (x, y) -> unitBytes(x, y) }
    }

    /** Deletes squares saved while browsing; saved regions stay. */
    suspend fun clearBrowsing() = withContext(Dispatchers.IO) {
        val k = 1 shl (Level.Street.z - Level.City.z)
        val rails = HashSet<Pair<Int, Int>>()
        for ((x, y) in browsingUnits()) {
            fun drop(l: Level, tx: Int, ty: Int) { file(l, tx, ty).delete(); present -= name(l, tx, ty) }
            drop(Level.City, x, y)
            for (i in 0 until k) for (j in 0 until k) drop(Level.Street, x * k + i, y * k + j)
            bikeFile(x, y).delete(); present -= bikeName(x, y)
            rails += (x shr 2) to (y shr 2)
        }
        for ((x, y) in rails) {
            file(Level.Rail, x, y).delete(); present -= name(Level.Rail, x, y)
            rebuildRail(x, y)
        }
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) { dir.listFiles()?.sumOf { it.length() } ?: 0L }

    fun freeBytes(): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    // ---------- Encoding ----------

    private fun readCodec(f: File): TileData =
        TileCodec.decode(GZIPInputStream(f.inputStream(), 1 shl 16).use { it.readBytes() })

    private fun writeCodec(f: File, t: TileData) {
        java.util.zip.GZIPOutputStream(f.outputStream(), 1 shl 16).use { it.write(TileCodec.encode(t)) }
    }

    // ---------- Packs ----------

    /** Tile files of some download squares and continent tiles (for building one shard of a pack). */
    fun filesOf(units: Collection<Pair<Int, Int>>, rails: Collection<Pair<Int, Int>>): List<File> {
        val k = 1 shl (Level.Street.z - Level.City.z)
        val out = ArrayList<File>()
        for ((x, y) in rails) if (has(Level.Rail, x, y)) out += file(Level.Rail, x, y)
        for ((x, y) in units) {
            if (has(Level.City, x, y)) out += file(Level.City, x, y)
            if (hasBike(x, y)) out += bikeFile(x, y)
            for (i in 0 until k) for (j in 0 until k) if (has(Level.Street, x * k + i, y * k + j)) out += file(Level.Street, x * k + i, y * k + j)
        }
        return out
    }

    /** Every tile file of a region present on disk (for building packs). */
    fun filesOf(region: Region): List<File> = tilesOf(region).flatMap { (l, x, y) ->
        listOfNotNull(
            file(l, x, y).takeIf { has(l, x, y) },
            if (l == Level.City && hasBike(x, y)) bikeFile(x, y) else null,
        )
    }

    /** Stores one file from a pack (a tile or bike file, by its name) and marks it present. */
    fun putFromPack(name: String, input: java.io.InputStream) {
        require(PACK_NAME.matches(name)) { "unexpected pack entry $name" }
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
        tmp.renameTo(File(dir, name))
        present += name
    }
}
