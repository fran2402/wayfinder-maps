import android.content.Context
import app.wayfinder.data.OfflineTransit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `list <file> [ids]`  writes the region ids to build, as a JSON array (for the workflow matrix).
 * `build <id> <outDir>`  downloads one region with the app's own code and writes
 *   `<id>.zip` (or `<id>.partN.zip` when big) plus `<id>.json` describing it.
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "list" -> {
            // `list <file> [ids]`: written to a file so build-tool chatter can't mix in.
            val wanted = args.getOrNull(2)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            // Small packs first, so cities and countries are ready long before whole continents.
            val ordered = OfflineTransit.cities + OfflineTransit.countries + OfflineTransit.continents
            // Big regions are split into shards, each built on its own machine in parallel.
            val ids = ordered.filter { wanted.isEmpty() || it.id in wanted }.flatMap { r ->
                val n = SHARDS[r.id] ?: 1
                if (n == 1) listOf(r.id) else (1..n).map { "${r.id}@$it/$n" }
            }
            File(args[1]).writeText(JSONArray(ids).toString())
        }
        "build" -> build(
            args[1], File(args[2]),
            args.getOrNull(3)?.let(::File)?.takeIf { it.isFile && it.length() > 0 },
            args.getOrNull(4)?.let(::File)?.takeIf { it.isFile && it.length() > 0 },
            args.getOrNull(5)?.let(::File)?.takeIf { it.isDirectory },
        )
        "packtest" -> runBlocking {
            // Runs the app's own pack download and install for one region into a scratch store.
            val dir = File(args[2]).apply { mkdirs() }
            val store = OfflineTransit(Context(dir))
            val entry = app.wayfinder.data.Packs.index()[args[1]] ?: error("no pack ${args[1]} in index")
            println("entry: $entry")
            app.wayfinder.data.Packs.install(entry, store) { d, t -> }
            val region = OfflineTransit.presets.first { it.id == args[1] }
            val files = store.filesOf(region)
            println("installed ${files.size} files; hasLines=${store.unitsOf(region).all { store.hasLines(it.first, it.second) }}")
            val (x, y) = store.unitsOf(region).first()
            val t = store.read(OfflineTransit.Level.City, x, y)
            println("city tile: ${t?.routes?.size} routes; modes ${t?.routes?.groupingBy { it.mode }?.eachCount()}")
        }
        "snaptest" -> snapTest(File(args[1]))
        "selftest" -> {
            selfTest()
            // `selftest <bikes.json>` also reads a bike extract, as a check of the OSM step.
            args.getOrNull(2)?.let { f -> alignTest(File(f)) }
            args.getOrNull(1)?.let { f ->
                val t = File(f).reader().use { app.wayfinder.data.BikeRoutes.parse(it) }
                println("bike extract: ${t.routes.size} routes, ${t.routes.sumOf { it.parts.size }} ways: " +
                    t.routes.joinToString { "${it.ref} ${app.wayfinder.data.Palette.hex(it.color)}" })
            }
        }
        else -> error("usage: list [ids] | build <id> <outDir>")
    }
}

/** How many machines build each big region. */
private val SHARDS = mapOf("europe" to 8, "north-america" to 6, "australia-nz" to 3, "japan" to 2)

/** Largest pack file; GitHub release files must stay under 2 GiB. */
private const val PART_BYTES = 1_800_000_000L

private var alignerForStats: app.wayfinder.data.OsmAlign? = null

private fun build(spec: String, out: File, bikeJson: File?, transitJson: File?, roadsDir: File?) = runBlocking(Dispatchers.Default) {
    // "europe@3/8" builds the third of eight shards of Europe.
    val id = spec.substringBefore('@')
    val (shard, shards) = spec.substringAfter('@', "1/1").split('/').map { it.toInt() }
    val region = OfflineTransit.presets.first { it.id == id }
    val work = File("work/$id").apply { mkdirs() }
    val store = OfflineTransit(Context(work))
    // Fewer, bigger requests: Transitous limits the rate, so requests matter more than their size.
    store.minStartDepth = 0
    // Shards follow continent-tile boundaries, so each shard's zoomed-out tiles are complete.
    val groups = store.unitsOf(region).groupBy { (it.first shr 2) to (it.second shr 2) }.toSortedMap(compareBy({ it.first }, { it.second }))
    val mine = groups.entries.filterIndexed { i, _ -> i % shards == shard - 1 }
    val rails = mine.map { it.key }
    val units = mine.flatMap { it.value }
    val name = if (shards == 1) id else "$id.s$shard"
    // Bike routes come from Overpass, which allows only slow, few queries: whole continents skip them.
    val bikes = region !in OfflineTransit.continents
    val done = AtomicInteger(); val failed = AtomicInteger(); val bikeFailed = AtomicInteger()
    val started = System.currentTimeMillis()
    println("$name: ${units.size} squares, bikes=$bikes")

    suspend fun runAll(list: List<Pair<Int, Int>>, workers: Int, work: suspend (Pair<Int, Int>) -> Unit) {
        val queue = Channel<Pair<Int, Int>>(Channel.UNLIMITED)
        list.forEach { queue.trySend(it) }
        queue.close()
        coroutineScope { repeat(workers) { launch { for (u in queue) work(u) } } }
    }
    suspend fun retry(times: Int, block: suspend () -> Unit): Boolean {
        repeat(times) { attempt ->
            try { block(); return true } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (attempt == times - 1) System.err.println("  gave up: ${e.message}")
                kotlinx.coroutines.delay(3_000L * (attempt + 1))
            }
        }
        return false
    }

    // Lines are laid onto OpenStreetMap's roads and tracks by geometry (buses on roads, trams on
    // tram tracks, …). Each square's ways come from a file prepared by the workflow.
    if (roadsDir != null) {
        val nets = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, app.wayfinder.data.RoadNet>()
        val snapped = AtomicInteger()
        fun netFor(u: Pair<Int, Int>): app.wayfinder.data.RoadNet = synchronized(nets) {
            nets[u] ?: run {
                if (nets.size > 20) nets.clear() // squares are worked through roughly in order
                val f = File(roadsDir, "${u.first}_${u.second}.txt")
                (if (f.isFile) f.bufferedReader().useLines { app.wayfinder.data.RoadNet(it) } else app.wayfinder.data.RoadNet(emptySequence()))
                    .also { nets[u] = it }
            }
        }
        store.align = { t, area ->
            val u = app.wayfinder.data.Tiles.lonToX((area.west + area.east) / 2, 8) to app.wayfinder.data.Tiles.latToY((area.south + area.north) / 2, 8)
            val net = netFor(u)
            if (net.wayCount == 0) t else app.wayfinder.data.RoadNet.snapTile(t, net).also { snapped.addAndGet(it.routes.size) }
        }
        println("$name: laying lines onto OSM roads and tracks from $roadsDir")
    } else transitJson?.let { f ->
        runCatching { f.reader().buffered(1 shl 16).use { app.wayfinder.data.BikeRoutes.parse(it) } }
            .onFailure { System.err.println("  transit extract unreadable: ${it.message}") }
            .getOrNull()?.let { osm ->
                val aligner = app.wayfinder.data.OsmAlign(osm)
                println("$name: ${aligner.size} OSM transit routes to align with")
                store.align = { t, area -> aligner.align(t, area) }
                alignerForStats = aligner
            }
    }

    // Bike routes from an OpenStreetMap extract (fast, prepared by the workflow) when there is one;
    // otherwise from Overpass, square by square.
    val localBikes = if (bikes && bikeJson != null) runCatching {
        bikeJson.reader().buffered(1 shl 16).use { app.wayfinder.data.BikeRoutes.parse(it) }
    }.onFailure { System.err.println("  bike extract unreadable: ${it.message}") }.getOrNull() else null
    if (localBikes != null) {
        println("$name: ${localBikes.routes.size} bike routes from the OSM extract")
        for ((x, y) in units) {
            val area = app.wayfinder.data.Tiles.bounds(8, x, y)
            val b = app.wayfinder.data.TransitRoutes.TileBuilder()
            for (r in localBikes.routes) app.wayfinder.data.TransitRoutes.clip(r, area)?.let(b::add)
            store.writeBike(x, y, b.build())
        }
    }

    coroutineScope {
        if (bikes && localBikes == null) launch {
            runAll(units, workers = 2) { u ->
                if (!retry(3) { store.downloadBike(u.first, u.second) { } }) bikeFailed.incrementAndGet()
            }
        }
        runAll(units, workers = 16) { u ->
            if (!retry(4) { store.downloadUnit(u.first, u.second) { } }) failed.incrementAndGet()
            val n = done.incrementAndGet()
            if (n % 25 == 0 || n == units.size) {
                val mins = (System.currentTimeMillis() - started) / 60_000.0
                println("$name: $n/${units.size} squares (${failed.get()} failed) after ${"%.1f".format(mins)} min")
            }
        }
        for ((x, y) in rails) store.rebuildRail(x, y)
    }

    out.mkdirs()
    val files = store.filesOf(units, rails)
    val parts = ArrayList<String>()
    var zip: ZipOutputStream? = null
    var written = 0L
    var total = 0L
    fun next(): ZipOutputStream {
        zip?.close()
        val index = parts.size + 1
        if (index == 2) {
            // A second part: rename the first so the names stay uniform.
            File(out, "$name.zip").renameTo(File(out, "$name.part1.zip")); parts[0] = "$name.part1.zip"
        }
        val file = if (index == 1) "$name.zip" else "$name.part$index.zip"
        parts += file
        written = 0
        return ZipOutputStream(File(out, file).outputStream().buffered(1 shl 16)).also { zip = it }
    }
    var z = next()
    for (f in files) {
        val bytes = f.readBytes() // tiles are already gzip-compressed: store them as they are
        if (written > 0 && written + bytes.size > PART_BYTES) z = next()
        val crc = CRC32().apply { update(bytes) }
        z.putNextEntry(ZipEntry(f.name).apply {
            method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong(); this.crc = crc.value
        })
        z.write(bytes)
        z.closeEntry()
        written += bytes.size; total += bytes.size
    }
    z.close()
    File(out, "$name.json").writeText(
        JSONObject()
            .put("id", id).put("name", region.name).put("shard", shard).put("shards", shards)
            .put("run", System.getenv("GITHUB_RUN_ID") ?: "local")
            .put("parts", JSONArray(parts))
            .put("bytes", parts.sumOf { File(out, it).length() })
            .put("files", files.size).put("squares", units.size)
            .put("failed", failed.get()).put("bikes", bikes).put("bikeFailed", bikeFailed.get())
            .put("built", Instant.now().toString())
            .toString(2)
    )
    if (app.wayfinder.data.RoadNet.stats.isNotEmpty()) {
        println("$name: road snapping (km) " + app.wayfinder.data.RoadNet.stats.entries.sortedBy { it.key }.joinToString { "${it.key}: ${it.value.get() / 1000}" })
    }
    alignerForStats?.let { al ->
        println("$name: alignment " + al.stats.entries.sortedBy { it.key }.joinToString { "${it.key}: ${it.value.get()}" })
        al.examples.forEach { println("  $it") }
    }
    println("$name: ${files.size} files, ${total / 1_000_000} MB in ${parts.size} part(s), ${failed.get()} squares failed")
}

/** Round-trips random tiles through the compact codec (run in CI before building packs). */
private fun selfTest() {
    val rnd = java.util.Random(7)
    repeat(200) {
        val routes = List(rnd.nextInt(20)) { r ->
            val parts = List(1 + rnd.nextInt(3)) {
                var lat = -80f + rnd.nextFloat() * 160f; var lon = -179f + rnd.nextFloat() * 358f
                FloatArray(2 * (2 + rnd.nextInt(50))) { k ->
                    if (k % 2 == 0) { lat += (rnd.nextFloat() - 0.5f) * 0.01f; lat } else { lon += (rnd.nextFloat() - 0.5f) * 0.01f; lon }
                }
            }
            app.wayfinder.data.TileRoute(
                "k$r|é ✓", app.wayfinder.data.TransitMode.entries[rnd.nextInt(app.wayfinder.data.TransitMode.entries.size)],
                "bus", "N$r", "Name $r ü", rnd.nextInt(), rnd.nextInt(), rnd.nextInt(10), parts,
                IntArray(parts.size) { rnd.nextInt() },
                List(rnd.nextInt(4)) { app.wayfinder.data.StopRef("s$it", "Stop $it", 45.123456 + it, -73.654321) },
                app.wayfinder.data.Bounds(-1.5, 2.25, 3.0, 4.75), IntArray(rnd.nextInt(3)) { rnd.nextInt(100000) },
            )
        }
        val t = app.wayfinder.data.TileData(routes, rnd.nextBoolean())
        val back = app.wayfinder.data.TileCodec.decode(app.wayfinder.data.TileCodec.encode(t))
        check(back.zoomFiltered == t.zoomFiltered && back.routes.size == t.routes.size)
        back.routes.zip(t.routes).forEach { (a, b) ->
            check(a.key == b.key && a.mode == b.mode && a.ref == b.ref && a.name == b.name && a.color == b.color && a.text == b.text && a.rank == b.rank)
            check(a.partIds.contentEquals(b.partIds) && a.routeIdx.contentEquals(b.routeIdx) && a.stops == b.stops && a.bounds == b.bounds)
            a.parts.zip(b.parts).forEach { (p, q) ->
                check(p.size == q.size); for (k in p.indices) check(kotlin.math.abs(p[k] - q[k]) < 2e-5f) { "coord ${p[k]} vs ${q[k]}" }
            }
        }
    }
    println("codec self-test passed")
}

/** A rough line 2 near an OSM tram 2 snaps onto it; a line 3 in the same place does not. */
private fun alignTest(transit: File) {
    val osm = transit.reader().use { app.wayfinder.data.BikeRoutes.parse(it) }
    val aligner = app.wayfinder.data.OsmAlign(osm)
    fun line(ref: String) = app.wayfinder.data.TileRoute(
        "Tram|$ref|#FF0000", app.wayfinder.data.TransitMode.Tram, "tram", ref, "", 0, 0, 3,
        listOf(floatArrayOf(45.8013f, 15.9518f, 45.8032f, 15.9561f, 45.8058f, 15.9622f)), intArrayOf(1), emptyList(),
        app.wayfinder.data.Bounds(45.8013, 15.9518, 45.8058, 15.9622),
    )
    val area = app.wayfinder.data.Bounds(45.0, 15.0, 46.5, 16.5)
    val out = aligner.align(app.wayfinder.data.TileData(listOf(line("02"), line("3")), false), area)
    out.routes.forEach { r -> println("align: line ${r.ref} -> ${r.parts.size} part(s), ${r.parts.sumOf { it.size / 2 }} points, first ${r.parts.first().take(2)}") }
}

/** A rough bus shape next to an L-shaped street corner is laid onto the streets, through the junction. */
private fun snapTest(dir: File) {
    dir.mkdirs()
    // Street A runs east along 45.8000 from 15.9500 to 15.9600; street B runs north from its end.
    File(dir, "ways.geojsonseq").writeText(
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[15.95,45.8],[15.955,45.8],[15.96,45.8]]},\"properties\":{\"highway\":\"primary\"}}\n" +
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[15.96,45.8],[15.96,45.805],[15.96,45.81]]},\"properties\":{\"highway\":\"secondary\"}}\n" +
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[15.95,45.8001],[15.96,45.8001]]},\"properties\":{\"railway\":\"tram\"}}\n" +
        // A side street 30 m north of A, not connected: the shape passes closer to it at one point.
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[15.953,45.80027],[15.957,45.80027]]},\"properties\":{\"highway\":\"residential\"}}\n"
    )
    val pr = ProcessBuilder("python3", "scripts/bucket_ways.py", File(dir, "ways.geojsonseq").path, File(dir, "roads").path).inheritIO().start()
    check(pr.waitFor() == 0)
    val f = File(dir, "roads").listFiles()!!.single()
    val net = f.bufferedReader().useLines { app.wayfinder.data.RoadNet(it) }
    // Off by ~15-20 m and cutting the corner diagonally.
    val bus = floatArrayOf(45.80015f, 15.9505f, 45.80018f, 15.9550f, 45.80012f, 15.9570f, 45.8010f, 15.9599f, 45.8080f, 15.96018f)
    val r = app.wayfinder.data.TileRoute("Bus|5|#000", app.wayfinder.data.TransitMode.Bus, "bus", "5", "", 0, 0, 1,
        listOf(bus), intArrayOf(1), emptyList(), app.wayfinder.data.Bounds(45.8, 15.95, 45.81, 15.961))
    val out = app.wayfinder.data.RoadNet.snapTile(app.wayfinder.data.TileData(listOf(r), false), net).routes.single().parts.single()
    println("snap: ${out.size / 2} points: " + out.toList().chunked(2).joinToString(" ") { "(%.5f,%.5f)".format(it[0], it[1]) })
}
