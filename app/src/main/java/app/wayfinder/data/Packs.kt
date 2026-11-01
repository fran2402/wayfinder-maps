package app.wayfinder.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Ready-made region files, built on GitHub by the "Build offline packs" workflow and published
 * on the repository's "packs" release. One download at full network speed instead of
 * thousands of requests from the phone.
 */
object Packs {
    private const val BASE = "https://github.com/fran2402/wayfinder-maps/releases/download/packs/"

    data class Entry(val id: String, val parts: List<String>, val bytes: Long, val built: String)

    private val client = Http.client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var cached: Pair<Long, Map<String, Entry>>? = null

    /** The packs on offer (cached for ten minutes); empty when none are published yet. */
    suspend fun index(): Map<String, Entry> = withContext(Dispatchers.IO) {
        cached?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return@withContext it.second }
        val map = client.newCall(Request.Builder().url(BASE + "index.json").build()).execute().use { r ->
            if (r.code == 404) return@use emptyMap<String, Entry>()
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            o.keys().asSequence().associateWith { id ->
                val e = o.getJSONObject(id)
                val parts = e.getJSONArray("parts")
                Entry(id, List(parts.length()) { parts.getString(it) }, e.optLong("bytes"), e.optString("built"))
            }
        }
        cached = System.currentTimeMillis() to map
        map
    }

    /**
     * Downloads a pack and stores every file in it straight into [store], streaming (nothing big
     * is held in memory). [onProgress] gets bytes done and the pack's total size.
     */
    suspend fun install(entry: Entry, store: OfflineTransit, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        var done = 0L
        for (part in entry.parts) {
            client.newCall(Request.Builder().url(BASE + part).build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpException(r.code, "pack download failed (HTTP ${r.code})")
                val counting = object : FilterInputStream(r.body!!.byteStream()) {
                    private var last = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                        if (n > 0) {
                            done += n
                            if (done - last > 512 * 1024) { last = done; onProgress(done, entry.bytes) }
                        }
                    }
                }
                ZipInputStream(counting.buffered(1 shl 16)).use { zip ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val e = zip.nextEntry ?: break
                        if (!e.isDirectory) store.putFromPack(e.name, NoClose(zip))
                        zip.closeEntry()
                    }
                }
            }
        }
        onProgress(entry.bytes, entry.bytes)
    }

    /** Lets a reader consume one zip entry without closing the whole archive. */
    private class NoClose(inp: InputStream) : FilterInputStream(inp) {
        override fun close() {}
    }
}
