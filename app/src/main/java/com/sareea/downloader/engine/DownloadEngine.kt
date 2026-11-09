package com.sareea.downloader.engine

import android.content.Context
import android.net.Uri
import com.sareea.downloader.data.Settings
import com.sareea.downloader.data.Store
import com.sareea.downloader.model.DownloadItem
import com.sareea.downloader.model.Segment
import com.sareea.downloader.model.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * The download engine.
 *  - Splits each file into up to 32 byte ranges, each on its own connection.
 *  - Remembers progress per range, so a paused (or killed) download resumes where it stopped.
 *  - Runs a queue: only [Settings.maxParallel] files download at the same time.
 */
class DownloadEngine(
    private val context: Context,
    private val store: Store,
    private val settings: Settings,
    private val onActive: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val limiter = SpeedLimiter { settings.speedLimitKBps }

    private val _items = MutableStateFlow(
        // Anything that was running when the app died comes back as paused.
        store.load().map { if (it.status.isActive) it.copy(status = Status.PAUSED, speedBps = 0) else it }
    )
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val running = ConcurrentHashMap<String, Running>()
    private val calls = ConcurrentHashMap<String, MutableSet<Call>>()

    private val partialDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "partial").apply { mkdirs() }
    }

    init {
        scope.launch {
            var n = 0
            while (isActive) {
                delay(500)
                if (running.isNotEmpty()) {
                    tick()
                    if (++n % 4 == 0) save()
                }
            }
        }
    }

    // ---------------------------------------------------------------- public API

    fun add(url: String) = addAll(listOf(url))

    fun addAll(urls: List<String>) {
        if (urls.isEmpty()) return
        val now = System.currentTimeMillis()
        val newItems = urls.mapIndexed { i, url ->
            val name = FileUtils.nameFrom(null, url, null)
            DownloadItem(
                id = UUID.randomUUID().toString(),
                url = url,
                fileName = name,
                category = FileUtils.categoryOf(name),
                createdAt = now + i,
            )
        }
        _items.update { it + newItems }
        save()
        schedule()
    }

    fun pause(id: String) {
        val item = get(id) ?: return
        if (item.status != Status.QUEUED && item.status != Status.CONNECTING && item.status != Status.DOWNLOADING) return
        edit(id) { it.copy(status = Status.PAUSED, speedBps = 0) }
        jobs.remove(id)?.cancel()
        calls[id]?.forEach { it.cancel() }
        save()
    }

    fun pauseAll() {
        _items.value.filter { it.status.isActive }.forEach { pause(it.id) }
    }

    fun resume(id: String) {
        val item = get(id) ?: return
        if (item.status != Status.PAUSED && item.status != Status.FAILED) return
        edit(id) { it.copy(status = Status.QUEUED, error = null) }
        save()
        schedule()
    }

    fun remove(id: String, deleteFile: Boolean) {
        val item = get(id) ?: return
        jobs.remove(id)?.cancel()
        calls[id]?.forEach { it.cancel() }
        _items.update { list -> list.filterNot { it.id == id } }
        tempFile(id).delete()
        if (deleteFile && item.finalUri != null) {
            runCatching { context.contentResolver.delete(Uri.parse(item.finalUri), null, null) }
        }
        save()
        schedule()
    }

    // ---------------------------------------------------------------- queue

    @Synchronized
    private fun schedule() {
        var free = settings.maxParallel - jobs.size
        val queued = _items.value
            .filter { it.status == Status.QUEUED && !jobs.containsKey(it.id) }
            .sortedBy { it.createdAt }
        for (q in queued) {
            if (free <= 0) break
            val job = scope.launch(start = CoroutineStart.LAZY) { run(q.id) }
            jobs[q.id] = job
            job.start()
            free--
        }
        if (jobs.isNotEmpty()) onActive()
    }

    // ---------------------------------------------------------------- one download

    private suspend fun run(id: String) {
        val self = coroutineContext[Job]
        val temp = tempFile(id)
        var r: Running? = null
        try {
            var item = get(id) ?: return
            edit(id) { it.copy(status = Status.CONNECTING, error = null, speedBps = 0) }

            // A fresh start (or a server that can't resume) needs a new probe.
            if (item.segments.isEmpty() || !temp.exists() || !item.resumable) {
                val p = probe(id, item.url)
                coroutineContext.ensureActive()
                val canSplit = p.resumable && p.length > 0
                val segs = if (canSplit) split(p.length, settings.connections) else listOf(Segment(0, -1, 0))
                RandomAccessFile(temp, "rw").use { it.setLength(if (canSplit) p.length else 0) }
                val name = p.fileName ?: item.fileName
                edit(id) {
                    it.copy(
                        fileName = name,
                        category = FileUtils.categoryOf(name),
                        totalBytes = p.length,
                        downloadedBytes = 0,
                        resumable = canSplit,
                        segments = segs,
                    )
                }
                item = get(id) ?: return
            }

            val rr = Running(item.segments, item.resumable)
            r = rr
            running[id] = rr
            edit(id) { it.copy(status = Status.DOWNLOADING) }

            coroutineScope {
                item.segments.forEachIndexed { i, seg ->
                    if (!seg.isDone) launch { downloadSegment(id, item.url, temp, i, seg, rr) }
                }
            }

            flush(id, rr)
            running.remove(id, rr)
            val done = rr.totalDone()
            if (item.totalBytes > 0 && done < item.totalBytes) throw IOException("Incomplete download")

            edit(id) {
                it.copy(
                    status = Status.SAVING,
                    speedBps = 0,
                    totalBytes = if (it.totalBytes > 0) it.totalBytes else done,
                    downloadedBytes = done,
                )
            }
            val cur = get(id) ?: return
            val uri = FileUtils.publish(context, temp, cur.fileName, cur.category, settings.sortByType)
            temp.delete()
            edit(id) { it.copy(status = Status.COMPLETED, finalUri = uri.toString()) }
        } catch (e: Throwable) {
            r?.let { flush(id, it) }
            val cancelled = e is CancellationException || !coroutineContext.isActive
            if (!cancelled) {
                edit(id) { it.copy(status = Status.FAILED, speedBps = 0, error = e.message ?: e.javaClass.simpleName) }
            }
            // If cancelled, pause()/remove() already set the right state.
        } finally {
            r?.let { running.remove(id, it) }
            if (self != null) jobs.remove(id, self)
            if (get(id) == null) temp.delete()
            save()
            schedule()
        }
    }

    private suspend fun downloadSegment(id: String, url: String, file: File, index: Int, seg: Segment, r: Running) {
        var attempt = 0
        while (true) {
            val from = seg.start + r.done[index]
            if (seg.end >= 0 && from > seg.end) return
            try {
                val b = Request.Builder().url(url).header("User-Agent", UA)
                if (r.resumable) b.header("Range", "bytes=$from-${seg.end}")
                withCall(id, client.newCall(b.build())) { resp ->
                    if (r.resumable && resp.code != 206) throw IOException("Server ignored range request (HTTP ${resp.code})")
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val input = resp.body?.byteStream() ?: throw IOException("Empty response")
                    RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(from)
                        val buf = ByteArray(64 * 1024)
                        var pos = from
                        while (true) {
                            coroutineContext.ensureActive()
                            val want = if (seg.end >= 0) min(buf.size.toLong(), seg.end - pos + 1).toInt() else buf.size
                            if (want <= 0) break
                            val n = input.read(buf, 0, want)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            pos += n
                            r.done.addAndGet(index, n.toLong())
                            r.bytesCounter.addAndGet(n.toLong())
                            limiter.acquire(n)
                        }
                    }
                }
                if (seg.end >= 0 && seg.start + r.done[index] <= seg.end) throw IOException("Connection closed early")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                // Network hiccup: retry this range from where it stopped.
                if (!r.resumable || ++attempt > 6) throw e
                delay(1500L * attempt)
            }
        }
    }

    private class Probe(val length: Long, val resumable: Boolean, val fileName: String?)

    private fun probe(id: String, url: String): Probe {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-0")
            .build()
        return withCall(id, client.newCall(req)) { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val name = FileUtils.nameFrom(
                resp.header("Content-Disposition"),
                resp.request.url.toString(),
                resp.header("Content-Type"),
            )
            if (resp.code == 206) {
                val total = resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                Probe(total, total > 0, name)
            } else {
                Probe(resp.body?.contentLength() ?: -1L, false, name)
            }
        }
    }

    /** Runs a request and tracks it so pause() can abort it instantly. */
    private inline fun <T> withCall(id: String, call: Call, block: (Response) -> T): T {
        val set = calls.getOrPut(id) { ConcurrentHashMap.newKeySet() }
        set.add(call)
        try {
            return call.execute().use(block)
        } finally {
            set.remove(call)
        }
    }

    private fun split(total: Long, wanted: Int): List<Segment> {
        val minPart = 512L * 1024 // no point splitting into pieces smaller than 512 KB
        val parts = wanted.coerceIn(1, 32).toLong().coerceAtMost(max(1L, total / minPart))
        val size = total / parts
        return (0L until parts).map { i ->
            val s = i * size
            val e = if (i == parts - 1) total - 1 else s + size - 1
            Segment(s, e, 0)
        }
    }

    // ---------------------------------------------------------------- progress

    private fun tick() {
        val now = System.nanoTime()
        val snap = HashMap<String, Triple<Long, Long, List<Segment>>>()
        for ((id, r) in running) {
            val bytes = r.bytesCounter.getAndSet(0)
            val dt = (now - r.lastTick) / 1e9
            r.lastTick = now
            val inst = if (dt > 0) (bytes / dt).toLong() else 0L
            r.speed = if (r.speed == 0L) inst else (r.speed * 0.6 + inst * 0.4).toLong()
            snap[id] = Triple(r.totalDone(), r.speed, r.snapshot())
        }
        _items.update { list ->
            list.map { item ->
                val s = snap[item.id]
                if (s == null || item.status != Status.DOWNLOADING) item
                else item.copy(downloadedBytes = s.first, speedBps = s.second, segments = s.third)
            }
        }
    }

    private fun flush(id: String, r: Running) {
        val current = running[id]
        if (current != null && current !== r) return // a newer run owns this item
        val segs = r.snapshot()
        val done = r.totalDone()
        edit(id) { it.copy(segments = segs, downloadedBytes = done, speedBps = 0) }
    }

    // ---------------------------------------------------------------- helpers

    private fun get(id: String) = _items.value.firstOrNull { it.id == id }

    private fun edit(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun save() = store.save(_items.value)

    private fun tempFile(id: String) = File(partialDir, "$id.part")

    private class Running(val segs: List<Segment>, val resumable: Boolean) {
        val done = AtomicLongArray(segs.size).also { arr -> segs.forEachIndexed { i, s -> arr.set(i, s.downloaded) } }
        val bytesCounter = AtomicLong(0)
        @Volatile var lastTick = System.nanoTime()
        @Volatile var speed = 0L

        fun totalDone(): Long {
            var sum = 0L
            for (i in 0 until done.length()) sum += done[i]
            return sum
        }

        fun snapshot(): List<Segment> = segs.mapIndexed { i, s -> s.copy(downloaded = done[i]) }
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
