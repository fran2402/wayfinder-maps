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
            val ids = OfflineTransit.presets.map { it.id }.filter { wanted.isEmpty() || it in wanted }
            File(args[1]).writeText(JSONArray(ids).toString())
        }
        "build" -> build(args[1], File(args[2]))
        "selftest" -> selfTest()
        else -> error("usage: list [ids] | build <id> <outDir>")
    }
}

/** Largest pack file; GitHub release files must stay under 2 GiB. */
private const val PART_BYTES = 1_800_000_000L

private fun build(id: String, out: File) = runBlocking(Dispatchers.Default) {
    val region = OfflineTransit.presets.first { it.id == id }
    val work = File("work/$id").apply { mkdirs() }
    val store = OfflineTransit(Context(work))
    val units = store.unitsOf(region)
    // Bike routes come from Overpass, which allows only slow, few queries: whole continents skip them.
    val bikes = region !in OfflineTransit.continents
    val done = AtomicInteger(); val failed = AtomicInteger(); val bikeFailed = AtomicInteger()
    val started = System.currentTimeMillis()
    println("$id: ${units.size} squares, bikes=$bikes")

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

    coroutineScope {
        if (bikes) launch {
            runAll(units, workers = 2) { u ->
                if (!retry(3) { store.downloadBike(u.first, u.second) { } }) bikeFailed.incrementAndGet()
            }
        }
        runAll(units, workers = 16) { u ->
            if (!retry(4) { store.downloadUnit(u.first, u.second) { } }) failed.incrementAndGet()
            val n = done.incrementAndGet()
            if (n % 25 == 0 || n == units.size) {
                val mins = (System.currentTimeMillis() - started) / 60_000.0
                println("$id: $n/${units.size} squares (${failed.get()} failed) after ${"%.1f".format(mins)} min")
            }
        }
        store.buildOverview(region)
    }

    out.mkdirs()
    val files = store.filesOf(region)
    val parts = ArrayList<String>()
    var zip: ZipOutputStream? = null
    var written = 0L
    var total = 0L
    fun next(): ZipOutputStream {
        zip?.close()
        val index = parts.size + 1
        if (index == 2) {
            // A second part: rename the first so the names stay uniform.
            File(out, "$id.zip").renameTo(File(out, "$id.part1.zip")); parts[0] = "$id.part1.zip"
        }
        val name = if (index == 1) "$id.zip" else "$id.part$index.zip"
        parts += name
        written = 0
        return ZipOutputStream(File(out, name).outputStream().buffered(1 shl 16)).also { zip = it }
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
    File(out, "$id.json").writeText(
        JSONObject()
            .put("id", id).put("name", region.name)
            .put("parts", JSONArray(parts))
            .put("bytes", parts.sumOf { File(out, it).length() })
            .put("files", files.size).put("squares", units.size)
            .put("failed", failed.get()).put("bikes", bikes).put("bikeFailed", bikeFailed.get())
            .put("built", Instant.now().toString())
            .toString(2)
    )
    println("$id: ${files.size} files, ${total / 1_000_000} MB in ${parts.size} part(s), ${failed.get()} squares failed")
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
