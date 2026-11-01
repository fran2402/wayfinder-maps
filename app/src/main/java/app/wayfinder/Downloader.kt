package app.wayfinder

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.wayfinder.data.OfflineTransit
import app.wayfinder.data.Packs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Region downloads, owned by the app rather than a screen, so they keep going in the
 * background (a foreground service with a progress notification keeps the app alive).
 */
object Downloader {
    data class State(
        val region: OfflineTransit.Region,
        val done: Int,
        val total: Int,
        val failed: Int = 0,
        /** The last thing that went wrong, shown under the progress bar. */
        val problem: String? = null,
        val retrying: Boolean = false,
        /** Building the zoomed-out tiles from what was downloaded. */
        val finishing: Boolean = false,
        /** Bike routes run in their own, slower queue (Overpass allows few requests at once). */
        val bikesDone: Int = 0,
        /** Downloading a ready-made pack: [done] and [total] are megabytes. */
        val pack: Boolean = false,
    )

    lateinit var store: OfflineTransit
        private set

    fun init(context: Context) {
        if (!::store.isInitialized) store = OfflineTransit(context.applicationContext)
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private val _arrived = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 512)
    /** Download squares whose lines just landed on disk. */
    val arrived: SharedFlow<Pair<Int, Int>> = _arrived

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val finished: SharedFlow<Unit> = _finished

    // One thread coordinates (counters need no locks); the work itself runs on IO/Default.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var job: Job? = null

    val active get() = job?.isActive == true

    fun cancel() { job?.cancel() }

    /** Starts (or resumes) a region; [refresh] fetches everything again. Squares near [center] go first. */
    fun start(context: Context, region: OfflineTransit.Region, refresh: Boolean, center: Pair<Int, Int>?) {
        if (active) return
        val app = context.applicationContext
        val units = store.unitsOf(region)
        _state.value = State(region, 0, units.size)
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        job = scope.launch {
            store.record(OfflineTransit.Saved(region, units.size, store.bytesOf(region), complete = false))
            var done = 0
            var failed = 0
            var bikesDone = 0
            var bikesFailed = 0
            var problem: String? = null
            var retrying = false
            var finishing = false
            fun publish() {
                _state.value = State(region, done, units.size, failed, problem, retrying, finishing, bikesDone)
            }
            suspend fun attempt(block: suspend () -> Unit): Boolean = try {
                block(); true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message?.take(60) ?: e.javaClass.simpleName
                false
            }
            suspend fun runAll(list: List<Pair<Int, Int>>, workers: Int, work: suspend (Pair<Int, Int>) -> Unit) {
                val queue = Channel<Pair<Int, Int>>(Channel.UNLIMITED)
                list.forEach { queue.trySend(it) }
                queue.close()
                coroutineScope { repeat(workers) { launch { for (u in queue) work(u) } } }
            }
            fun nearFirst(list: List<Pair<Int, Int>>) = if (center == null) list else list.sortedBy {
                val dx = it.first - center.first; val dy = it.second - center.second; dx * dx + dy * dy
            }
            var complete = false
            try {
                // A ready-made pack (built on GitHub) is one fast download; squares are the fallback.
                val pack = if (region.id.startsWith("area-")) null else runCatching { Packs.index()[region.id] }.getOrNull()
                var packed = false
                if (pack != null) {
                    _state.value = State(region, 0, maxOf(1, (pack.bytes / 1_000_000).toInt()), pack = true)
                    packed = attempt {
                        Packs.install(pack, store) { d, t ->
                            _state.value = State(region, (d / 1_000_000).toInt(), maxOf(1, (t / 1_000_000).toInt()), problem = problem, pack = true)
                        }
                    }
                    if (packed) { finishing = true; complete = true }
                }
                if (!packed) coroutineScope {
                    // Bike routes: their own queue, so slow Overpass never holds up the lines.
                    val bikes = launch {
                        val todo = units.filter { refresh || !store.hasBike(it.first, it.second) }
                        bikesDone = units.size - todo.size
                        runAll(nearFirst(todo), workers = 2) { u ->
                            if (attempt { store.downloadBike(u.first, u.second, force = refresh) { } }) bikesDone++ else bikesFailed++
                            publish()
                        }
                    }
                    val todo = units.filter { refresh || !store.hasLines(it.first, it.second) }
                    done = units.size - todo.size
                    publish()
                    val missed = ArrayList<Pair<Int, Int>>()
                    runAll(nearFirst(todo), workers = 16) { u ->
                        val ok = attempt { store.downloadUnit(u.first, u.second, force = refresh) { problem = it; publish() } }
                        if (ok) _arrived.tryEmit(u) else { failed++; missed += u }
                        done++
                        if (ok && done % 8 == 0) problem = null
                        publish()
                    }
                    if (missed.isNotEmpty()) {
                        retrying = true
                        publish()
                        delay(5_000)
                        runAll(missed, workers = 8) { u ->
                            if (attempt { store.downloadUnit(u.first, u.second, force = refresh) { } }) {
                                failed--; _arrived.tryEmit(u); publish()
                            }
                        }
                    }
                    finishing = true
                    publish()
                    store.buildOverview(region)
                    bikes.join()
                    complete = failed == 0 && bikesFailed == 0
                }
            } finally {
                withContext(NonCancellable) {
                    store.record(OfflineTransit.Saved(region, units.size, store.bytesOf(region), complete = complete))
                    _state.value = null
                    _finished.tryEmit(Unit)
                }
            }
        }
    }
}
