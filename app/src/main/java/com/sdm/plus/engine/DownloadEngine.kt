package com.sdm.plus.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.sdm.plus.data.Settings
import com.sdm.plus.data.Store
import com.sdm.plus.model.DownloadItem
import com.sdm.plus.model.Kind
import com.sdm.plus.model.Notes
import com.sdm.plus.model.Segment
import com.sdm.plus.model.Status
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
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
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.torrent_flags_t
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
 * The download engine: one queue for every kind of download.
 *  - HTTP: splits each file into up to 32 ranges, each on its own connection, resumable.
 *  - TORRENT: libtorrent, only the files you picked.
 *  - MEDIA: yt-dlp for video/audio pages (YouTube, TikTok, Instagram…).
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
        store.load().map { if (it.status.isActive) it.copy(status = Status.PAUSED, speedBps = 0, note = null) else it }
    )
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val running = ConcurrentHashMap<String, Running>()
    private val calls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val torrentHandles = ConcurrentHashMap<String, TorrentHandle>()

    val partialDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "partial").apply { mkdirs() }
    }

    init {
        scope.launch {
            var n = 0
            while (isActive) {
                delay(500)
                if (running.isNotEmpty()) tick()
                if (jobs.isNotEmpty() && ++n % 4 == 0) save()
            }
        }
    }

    // ================================================================ adding

    /** Adds links without asking anything: picks the right engine for each. */
    fun addAuto(urls: List<String>, cookie: String? = null, referer: String? = null, userAgent: String? = null) {
        if (urls.isEmpty()) return
        val now = System.currentTimeMillis()
        val newItems = urls.mapIndexed { i, url ->
            when {
                TorrentSupport.isTorrentLink(url) -> newTorrent(url, now + i, null, emptyList(), null)
                MediaSupport.isMediaSite(url) -> newMedia(url, now + i, MediaSupport.BEST, "BEST", null)
                else -> {
                    val name = FileUtils.nameFrom(null, url, null)
                    DownloadItem(
                        id = UUID.randomUUID().toString(), url = url, fileName = name,
                        category = FileUtils.categoryOf(name), createdAt = now + i,
                        cookie = cookie, referer = referer, userAgent = userAgent,
                    )
                }
            }
        }
        append(newItems)
    }

    /** Plain file download, skipping detection (used by the browser's download catcher). */
    fun addHttp(url: String, fileName: String?, cookie: String?, referer: String?, userAgent: String?) {
        val name = fileName ?: FileUtils.nameFrom(null, url, null)
        append(
            listOf(
                DownloadItem(
                    id = UUID.randomUUID().toString(), url = url, fileName = name,
                    category = FileUtils.categoryOf(name), createdAt = System.currentTimeMillis(),
                    cookie = cookie, referer = referer, userAgent = userAgent,
                )
            )
        )
    }

    fun addMedia(url: String, format: String, label: String, title: String?, playlist: Boolean = false) {
        append(listOf(newMedia(url, System.currentTimeMillis(), format, label, title).copy(playlist = playlist)))
    }

    /** [torrentBytes] = already-fetched metadata (from the picker); null = fetch when it starts. */
    fun addTorrent(source: String, torrentBytes: ByteArray?, selection: List<Int>, name: String?) {
        append(listOf(newTorrent(source, System.currentTimeMillis(), torrentBytes, selection, name)))
    }

    private fun newMedia(url: String, at: Long, format: String, label: String, title: String?): DownloadItem {
        val audio = format == MediaSupport.MP3 || label == "M4A"
        return DownloadItem(
            id = UUID.randomUUID().toString(), url = url,
            fileName = title ?: url,
            category = if (audio) "Music" else "Video",
            createdAt = at, kind = Kind.MEDIA, format = format, formatLabel = label,
        )
    }

    private fun newTorrent(source: String, at: Long, bytes: ByteArray?, selection: List<Int>, name: String?): DownloadItem {
        val id = UUID.randomUUID().toString()
        if (bytes != null) torrentMetaFile(id).writeBytes(bytes)
        return DownloadItem(
            id = id, url = source,
            fileName = name ?: magnetName(source) ?: "Torrent",
            category = "Torrents", createdAt = at, kind = Kind.TORRENT, selection = selection,
        )
    }

    private fun magnetName(uri: String): String? =
        if (uri.startsWith("magnet:", true)) runCatching { Uri.parse(uri.replaceFirst("magnet:?", "magnet:///?")).getQueryParameter("dn") }.getOrNull()
        else null

    private fun append(newItems: List<DownloadItem>) {
        _items.update { it + newItems }
        save()
        schedule()
    }

    // ================================================================ controls

    fun pause(id: String) {
        val item = get(id) ?: return
        if (item.status != Status.QUEUED && item.status != Status.CONNECTING && item.status != Status.DOWNLOADING) return
        edit(id) { it.copy(status = Status.PAUSED, speedBps = 0, note = null) }
        stopWork(id)
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

    fun resumeAll() {
        _items.value.filter { it.status == Status.PAUSED || it.status == Status.FAILED }
            .forEach { i -> edit(i.id) { it.copy(status = Status.QUEUED, error = null) } }
        save()
        schedule()
    }

    fun remove(id: String, deleteFile: Boolean) {
        val item = get(id) ?: return
        stopWork(id)
        _items.update { list -> list.filterNot { it.id == id } }
        cleanup(id)
        if (deleteFile && item.finalUri != null) {
            runCatching { context.contentResolver.delete(Uri.parse(item.finalUri), null, null) }
        }
        save()
        schedule()
    }

    fun clearCompleted() {
        _items.update { list -> list.filterNot { it.status == Status.COMPLETED } }
        save()
    }

    /** Pieces in order, so a video can be watched while it downloads. */
    fun setSequential(id: String, on: Boolean) {
        edit(id) { it.copy(sequential = on) }
        torrentHandles[id]?.let { th ->
            runCatching { if (on) th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else th.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
        }
        save()
    }

    /** The partly-downloaded video of a torrent, to open in a player while it downloads. */
    fun playableFile(id: String): File? {
        val item = get(id) ?: return null
        if (item.kind != Kind.TORRENT) return null
        val dir = File(partialDir, id)
        if (!dir.exists()) return null
        return dir.walkTopDown()
            .filter { it.isFile && FileUtils.categoryOf(it.name) in listOf("Video", "Music") }
            .maxByOrNull { it.length() }
    }

    /** Replace an expired link; the download continues from where it stopped if it is the same file. */
    fun changeUrl(id: String, newUrl: String) {
        val item = get(id) ?: return
        if (item.status != Status.PAUSED && item.status != Status.FAILED) return
        edit(id) { it.copy(url = newUrl.trim(), urlChanged = true, error = null) }
        save()
    }

    /** Called when the internet comes back: retry downloads that failed because of the network. */
    fun onNetworkBack() {
        if (!settings.autoResume) return
        val networkWords = listOf(
            "unable to resolve", "timeout", "timed out", "reset", "failed to connect", "network",
            "connection", "socket", "unreachable", "closed early", "incomplete", "eof", "ssl",
        )
        var any = false
        _items.value.filter { item ->
            item.status == Status.FAILED && item.autoRetries < 5 &&
                (item.error ?: "").lowercase().let { e -> networkWords.any { e.contains(it) } }
        }.forEach { item ->
            any = true
            edit(item.id) { it.copy(status = Status.QUEUED, error = null, autoRetries = it.autoRetries + 1) }
        }
        if (any) {
            save()
            schedule()
        }
    }

    private fun stopWork(id: String) {
        jobs.remove(id)?.cancel()
        calls[id]?.forEach { it.cancel() }
        MediaSupport.cancel(id)
    }

    private fun cleanup(id: String) {
        tempFile(id).delete()
        File(partialDir, id).deleteRecursively()
        torrentMetaFile(id).delete()
    }

    /** Downloads a small file into memory (used for .torrent links). */
    fun fetchBytes(url: String, maxBytes: Int = 20 * 1024 * 1024): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            if (body.contentLength() > maxBytes) throw IOException("File too large")
            return body.bytes()
        }
    }

    // ================================================================ queue

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

    private suspend fun run(id: String) {
        val self = coroutineContext[Job]
        try {
            val item = get(id) ?: return
            edit(id) { it.copy(status = Status.CONNECTING, error = null, speedBps = 0, note = null) }
            waitForNetwork(id, item.kind == Kind.TORRENT && settings.torrentWifiOnly)
            when (item.kind) {
                Kind.HTTP -> runHttp(id)
                Kind.TORRENT -> runTorrent(id)
                Kind.MEDIA -> runMedia(id)
            }
        } catch (e: Throwable) {
            val cancelled = e is CancellationException || !coroutineContext.isActive
            if (!cancelled) {
                edit(id) {
                    it.copy(status = Status.FAILED, speedBps = 0, note = null, error = friendlyError(e))
                }
            }
            // If cancelled, pause()/remove() already set the right state.
        } finally {
            if (self != null) jobs.remove(id, self)
            if (get(id) == null) cleanup(id)
            save()
            schedule()
        }
    }

    private fun friendlyError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return msg.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("ERROR") } ?: msg.take(200)
    }

    private suspend fun waitForNetwork(id: String, torrentWifi: Boolean) {
        while (true) {
            if (!(settings.wifiOnly || torrentWifi) || isUnmetered()) break
            edit(id) { it.copy(note = Notes.WIFI) }
            delay(5000)
        }
        edit(id) { it.copy(note = null) }
    }

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun subFolderFor(category: String): String? = if (settings.sortByType) category else null

    // ================================================================ HTTP

    private suspend fun runHttp(id: String) {
        val temp = tempFile(id)
        var r: Running? = null
        try {
            var item = get(id) ?: return

            if (item.urlChanged) {
                // New link for the same download: keep progress only if it is the same file.
                val p = probe(id, item)
                val same = p.resumable && p.length > 0 && p.length == item.totalBytes && temp.exists()
                edit(id) { if (same) it.copy(urlChanged = false) else it.copy(urlChanged = false, segments = emptyList(), downloadedBytes = 0) }
                item = get(id) ?: return
            }

            if (item.segments.isEmpty() || !temp.exists() || !item.resumable) {
                val p = probe(id, item)
                coroutineContext.ensureActive()
                // A web page, not a file: let yt-dlp find the video in it.
                if (p.isHtml) {
                    edit(id) {
                        it.copy(kind = Kind.MEDIA, format = MediaSupport.BEST, formatLabel = "BEST", category = "Video")
                    }
                    runMedia(id)
                    return
                }
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

            // A .torrent file link: hand it to the torrent engine.
            if (item.category == "Torrents" && item.fileName.endsWith(".torrent", true) && (item.totalBytes in 1L..20_000_000L)) {
                val bytes = fetchBytes(item.url)
                torrentMetaFile(id).writeBytes(bytes)
                edit(id) { it.copy(kind = Kind.TORRENT, segments = emptyList(), totalBytes = -1, downloadedBytes = 0) }
                temp.delete()
                runTorrent(id)
                return
            }

            val rr = Running(item.segments, item.resumable)
            r = rr
            running[id] = rr
            edit(id) { it.copy(status = Status.DOWNLOADING) }

            coroutineScope {
                item.segments.forEachIndexed { i, seg ->
                    if (!seg.isDone) launch { downloadSegment(id, item, temp, i, seg, rr) }
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
            val uri = FileUtils.publish(context, temp, cur.fileName, subFolderFor(cur.category))
            temp.delete()
            edit(id) { it.copy(status = Status.COMPLETED, finalUri = uri.toString(), autoRetries = 0) }
        } catch (e: Throwable) {
            r?.let { flush(id, it) }
            throw e
        } finally {
            r?.let { running.remove(id, it) }
        }
    }

    private fun Request.Builder.withHeaders(item: DownloadItem): Request.Builder {
        header("User-Agent", item.userAgent ?: UA)
        item.cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
        item.referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
        return this
    }

    private suspend fun downloadSegment(id: String, item: DownloadItem, file: File, index: Int, seg: Segment, r: Running) {
        var attempt = 0
        while (true) {
            val from = seg.start + r.done[index]
            if (seg.end >= 0 && from > seg.end) return
            try {
                val b = Request.Builder().url(item.url).withHeaders(item)
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
                if (!r.resumable || ++attempt > 8) throw e
                delay(1500L * attempt)
            }
        }
    }

    private class Probe(val length: Long, val resumable: Boolean, val fileName: String?, val isHtml: Boolean)

    private fun probe(id: String, item: DownloadItem): Probe {
        val req = Request.Builder().url(item.url)
            .withHeaders(item)
            .header("Range", "bytes=0-0")
            .build()
        return withCall(id, client.newCall(req)) { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val type = resp.header("Content-Type").orEmpty().lowercase()
            val disposition = resp.header("Content-Disposition")
            val html = type.startsWith("text/html") && disposition == null
            val name = FileUtils.nameFrom(disposition, resp.request.url.toString(), resp.header("Content-Type"))
            if (resp.code == 206) {
                val total = resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                Probe(total, total > 0, name, html)
            } else {
                Probe(resp.body?.contentLength() ?: -1L, false, name, html)
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

    // ================================================================ TORRENT

    private suspend fun runTorrent(id: String) {
        val metaFile = torrentMetaFile(id)
        val item0 = get(id) ?: return

        // Get the torrent's file list first if we only have a link.
        if (!metaFile.exists()) {
            edit(id) { it.copy(note = Notes.METADATA) }
            val bytes = if (item0.url.startsWith("magnet:", true)) {
                TorrentSupport.fetchMagnet(item0.url, File(partialDir, "magnet-tmp"))
            } else {
                fetchBytes(item0.url)
            }
            coroutineContext.ensureActive()
            metaFile.writeBytes(bytes)
        }

        val ti = TorrentInfo(metaFile)
        val fs = ti.files()
        val n = fs.numFiles()
        val selection = item0.selection.filter { it in 0 until n }.ifEmpty { (0 until n).toList() }
        val chosen = selection.toHashSet()
        val priorities = Array(n) { if (it in chosen) Priority.DEFAULT else Priority.IGNORE }
        edit(id) {
            it.copy(
                fileName = if (it.fileName == "Torrent" || it.fileName.startsWith("magnet:")) ti.name() else it.fileName,
                selection = selection,
                note = null,
            )
        }

        val dir = File(partialDir, id).apply { mkdirs() }
        if (settings.extraTrackers) TorrentSupport.refreshTrackers(client)
        val sm = TorrentSupport.session()
        val limit = settings.speedLimitKBps
        sm.downloadRateLimit(if (limit > 0) limit * 1024 else 0)
        val hash = ti.infoHash().toHex()
        // Fast resume: continue straight away instead of re-checking every file.
        val resume = if (File(dir, fs.filePath(selection.first())).exists()) TorrentSupport.resumeFileFor(hash) else null
        val flags = if (get(id)?.sequential == true) TorrentFlags.SEQUENTIAL_DOWNLOAD else torrent_flags_t()
        sm.download(ti, dir, resume, priorities, null, flags)

        var handle: TorrentHandle? = null
        for (i in 0 until 100) {
            val h = sm.find(ti.infoHash())
            if (h != null && h.isValid) {
                handle = h
                break
            }
            delay(200)
        }
        val th = handle ?: throw IOException("Could not start the torrent")
        torrentHandles[id] = th
        if (settings.extraTrackers) {
            // More trackers = more peers found = faster download.
            TorrentSupport.cachedTrackers().forEach { runCatching { th.addTracker(AnnounceEntry(it)) } }
        }
        var finished = false

        try {
            th.resume()
            edit(id) { it.copy(status = Status.DOWNLOADING) }
            while (true) {
                coroutineContext.ensureActive()
                val st = th.status()
                val state = st.state()
                val wanted = st.totalWanted()
                val done = st.totalWantedDone()
                val checking = state == TorrentStatus.State.CHECKING_FILES
                edit(id) {
                    if (it.status != Status.DOWNLOADING) it
                    else it.copy(
                        totalBytes = wanted,
                        downloadedBytes = done,
                        speedBps = st.downloadPayloadRate().toLong(),
                        peers = st.numPeers(),
                        seeds = st.numSeeds(),
                        note = if (checking) Notes.CHECKING else null,
                    )
                }
                if (!checking && wanted > 0 && done >= wanted) {
                    finished = true
                    break
                }
                delay(1000)
            }
        } finally {
            torrentHandles.remove(id, th)
            if (!finished) TorrentSupport.saveResume(th)
            // Stop sharing as soon as we're done or paused (saves data and battery).
            runCatching { sm.remove(th) }
            TorrentSupport.saveState()
        }
        TorrentSupport.deleteResume(hash)

        // Copy the chosen files to Download/SDM/Torrents/<name>/...
        edit(id) { it.copy(status = Status.SAVING, speedBps = 0, peers = 0, seeds = 0) }
        delay(500) // let libtorrent close the files
        var first: Uri? = null
        for (i in selection) {
            val path = fs.filePath(i)
            val f = File(dir, path)
            if (!f.exists()) continue
            val folder = "Torrents/" + path.substringBeforeLast('/', "")
            val uri = FileUtils.publish(context, f, fs.fileName(i), folder.trimEnd('/'))
            if (first == null) first = uri
        }
        dir.deleteRecursively()
        metaFile.delete()
        edit(id) {
            it.copy(
                status = Status.COMPLETED,
                finalUri = if (selection.size == 1) first?.toString() else null,
                autoRetries = 0,
            )
        }
    }

    // ================================================================ MEDIA

    private val itemRe = Regex("""Downloading (?:item|video) (\d+) of (\d+)""")
    private val sizeRe = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")
    private val speedRe = Regex("""at\s+([\d.]+)\s*([KMGT]?i?B)/s""")

    private fun toBytes(num: String, unit: String): Long {
        val v = num.toDoubleOrNull() ?: return -1
        val mult = when (unit.uppercase().firstOrNull()) {
            'K' -> 1024.0
            'M' -> 1024.0 * 1024
            'G' -> 1024.0 * 1024 * 1024
            'T' -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (v * mult).toLong()
    }

    private suspend fun runMedia(id: String) {
        val item = get(id) ?: return
        edit(id) { it.copy(note = Notes.ENGINE) }
        MediaSupport.ensureInit(context)
        MediaSupport.updateIfDue(context, settings)
        coroutineContext.ensureActive()

        val dir = File(partialDir, id).apply { mkdirs() }
        val fmt = item.format ?: MediaSupport.BEST
        val req = YoutubeDLRequest(item.url)
            .addOption("--no-mtime")
            .addOption("--newline")
        if (item.playlist) {
            req.addOption("--yes-playlist")
                .addOption("-o", File(dir, "%(playlist_title).80B/%(playlist_index)03d - %(title).100B.%(ext)s").absolutePath)
        } else {
            req.addOption("--no-playlist")
                .addOption("-o", File(dir, "%(title).120B.%(ext)s").absolutePath)
        }
        if (fmt.startsWith("mp3:")) {
            req.addOption("-f", fmt.removePrefix("mp3:"))
                .addOption("-x")
                .addOption("--audio-format", "mp3")
        } else {
            req.addOption("-f", fmt)
                .addOption("--merge-output-format", "mp4")
            if (settings.subtitles && item.formatLabel != "M4A") {
                req.addOption("--write-subs")
                    .addOption("--write-auto-subs")
                    .addOption("--sub-langs", "ar.*,en.*")
                    .addOption("--embed-subs")
            }
        }
        item.cookie?.takeIf { it.isNotBlank() }?.let { req.addOption("--add-header", "Cookie:$it") }
        item.userAgent?.let { req.addOption("--user-agent", it) }
        val limit = settings.speedLimitKBps
        if (limit > 0) req.addOption("-r", "${limit}K")

        edit(id) { it.copy(status = Status.DOWNLOADING, note = null) }
        var last = 0L
        YoutubeDL.getInstance().execute(req, id) { progress, _, line ->
            itemRe.find(line)?.let { m ->
                val pos = "${m.groupValues[1]}/${m.groupValues[2]}"
                edit(id) { it.copy(playlistPos = pos) }
            }
            val now = System.currentTimeMillis()
            if (now - last < 500) return@execute
            last = now
            val total = sizeRe.find(line)?.let { toBytes(it.groupValues[1], it.groupValues[2]) } ?: -1L
            val speed = speedRe.find(line)?.let { toBytes(it.groupValues[1], it.groupValues[2]) } ?: -1L
            edit(id) {
                if (it.status != Status.DOWNLOADING) return@edit it
                val t = if (total > 0) total else it.totalBytes
                it.copy(
                    totalBytes = t,
                    downloadedBytes = if (t > 0) (t * (progress.coerceIn(0f, 100f) / 100f)).toLong() else it.downloadedBytes,
                    speedBps = if (speed >= 0) speed else it.speedBps,
                )
            }
        }
        coroutineContext.ensureActive()

        val outputs = dir.walkTopDown()
            .filter { f ->
                f.isFile && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") && !f.name.contains(".part-") &&
                    !f.name.endsWith(".temp") && !f.name.endsWith(".vtt") && !f.name.endsWith(".srt") && f.length() > 0
            }
            .toList()
        if (outputs.isEmpty()) throw IOException("yt-dlp finished but no file was produced")

        if (!item.playlist) {
            val out = outputs.maxBy { it.length() }
            edit(id) {
                it.copy(
                    status = Status.SAVING, speedBps = 0, fileName = out.name,
                    category = FileUtils.categoryOf(out.name),
                    totalBytes = out.length(), downloadedBytes = out.length(),
                )
            }
            val cur = get(id) ?: return
            val uri = FileUtils.publish(context, out, out.name, subFolderFor(cur.category))
            dir.deleteRecursively()
            edit(id) { it.copy(status = Status.COMPLETED, finalUri = uri.toString(), autoRetries = 0, playlistPos = null) }
            return
        }

        // Playlist: every file goes to Download/SDM/<type>/<playlist name>/
        val total = outputs.sumOf { it.length() }
        val listName = outputs.first().parentFile?.takeIf { it != dir }?.name ?: item.fileName
        edit(id) {
            it.copy(status = Status.SAVING, speedBps = 0, fileName = listName, totalBytes = total, downloadedBytes = total)
        }
        val cat = FileUtils.categoryOf(outputs.first().name)
        for (f in outputs.sortedBy { it.name }) {
            FileUtils.publish(context, f, f.name, listOfNotNull(subFolderFor(cat), listName).joinToString("/"))
        }
        dir.deleteRecursively()
        edit(id) {
            it.copy(
                status = Status.COMPLETED, finalUri = null, category = cat,
                playlistPos = "${outputs.size}", autoRetries = 0,
            )
        }
    }

    // ================================================================ progress (HTTP)

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

    // ================================================================ helpers

    private fun get(id: String) = _items.value.firstOrNull { it.id == id }

    private fun edit(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun save() = store.save(_items.value)

    private fun tempFile(id: String) = File(partialDir, "$id.part")

    private fun torrentMetaFile(id: String) = File(partialDir, "$id.torrent")

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
        const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
