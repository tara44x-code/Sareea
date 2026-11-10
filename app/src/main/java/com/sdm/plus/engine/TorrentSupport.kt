package com.sdm.plus.engine

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One shared libtorrent session for the whole app, tuned for speed:
 *  - many more connections than the defaults,
 *  - a fixed listening port with UPnP/NAT-PMP so other peers can reach us,
 *  - public trackers added to every torrent (more peers, faster magnet info),
 *  - DHT state kept between runs, and "fast resume" data so a paused torrent
 *    continues instantly instead of re-checking every file.
 */
object TorrentSupport {

    data class TFile(val index: Int, val path: String, val size: Long)

    data class Meta(val name: String, val totalSize: Long, val files: List<TFile>)

    private lateinit var baseDir: File
    private val stateFile get() = File(baseDir, "session.state")
    private val trackersFile get() = File(baseDir, "trackers.txt")
    private fun resumeFile(hash: String) = File(baseDir, "resume/$hash.resume")

    @Volatile
    private var sm: SessionManager? = null
    private val resumeWaiters = ConcurrentHashMap<String, CountDownLatch>()

    fun init(ctx: Context) {
        baseDir = File(ctx.filesDir, "torrent").apply { mkdirs() }
        File(baseDir, "resume").mkdirs()
    }

    private fun speedSettings(): SettingsPack = SettingsPack()
        .activeDownloads(10)
        .activeSeeds(10)
        .activeLimit(30)
        .connectionsLimit(500)
        .maxPeerlistSize(4000)
        .activeDhtLimit(200)
        .activeTrackerLimit(1600)
        .listenInterfaces("0.0.0.0:6881,[::]:6881")
        .alertQueueSize(5000)

    @Synchronized
    fun session(): SessionManager {
        sm?.let { return it }
        val s = SessionManager(false)
        s.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.SAVE_RESUME_DATA.swig())
            override fun alert(alert: Alert<*>) {
                if (alert !is SaveResumeDataAlert) return
                runCatching {
                    val hash = alert.handle().infoHash().toHex()
                    resumeFile(hash).writeBytes(AddTorrentParams.writeResumeDataBuf(alert.params()))
                    resumeWaiters.remove(hash)?.countDown()
                }
            }
        })
        // Reuse the DHT routing table from last time: peers are found in seconds, not minutes.
        val params = runCatching {
            if (stateFile.exists()) SessionParams(stateFile.readBytes()) else null
        }.getOrNull() ?: SessionParams(speedSettings())
        s.start(params)
        s.applySettings(speedSettings())
        sm = s
        return s
    }

    fun saveState() {
        val s = sm ?: return
        runCatching { s.saveState()?.let { stateFile.writeBytes(it) } }
    }

    /** Asks libtorrent to write fast-resume data and waits for it (max 4 s). */
    fun saveResume(th: TorrentHandle) {
        runCatching {
            val hash = th.infoHash().toHex()
            val latch = CountDownLatch(1)
            resumeWaiters[hash] = latch
            th.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            latch.await(4, TimeUnit.SECONDS)
            resumeWaiters.remove(hash)
        }
    }

    fun resumeFileFor(hash: String): File? = resumeFile(hash).takeIf { it.exists() && it.length() > 0 }

    fun deleteResume(hash: String) {
        resumeFile(hash).delete()
    }

    fun isTorrentLink(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("magnet:") || u.substringBefore('?').endsWith(".torrent")
    }

    /** Asks the swarm (DHT/trackers) for the torrent's file list. Can take up to [timeoutSec]. */
    fun fetchMagnet(magnet: String, tempDir: File, timeoutSec: Int = 90, withTrackers: Boolean = true): ByteArray {
        tempDir.mkdirs()
        // Extra trackers in the link help a lot with getting the file list quickly.
        val link = if (withTrackers) magnet + cachedTrackers().take(30).joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") } else magnet
        val bytes = session().fetchMagnet(link, timeoutSec, tempDir)
        saveState()
        return bytes ?: throw IOException("Could not get torrent info (no peers answered)")
    }

    fun meta(bytes: ByteArray): Meta {
        val ti = TorrentInfo.bdecode(bytes)
        val fs = ti.files()
        val files = (0 until fs.numFiles()).map { i -> TFile(i, fs.filePath(i), fs.fileSize(i)) }
        return Meta(ti.name(), ti.totalSize(), files)
    }

    // ------------------------------------------------------------------ public trackers

    private val builtInTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://opentracker.io:6969/announce",
        "https://tracker.tamersunion.org:443/announce",
        "http://tracker.opentrackr.org:1337/announce",
    )

    /** Trackers saved from the last daily refresh, or the built-in list. Never touches the network. */
    fun cachedTrackers(): List<String> {
        val cached = runCatching {
            if (::baseDir.isInitialized && trackersFile.exists()) trackersFile.readLines().map { it.trim() }.filter { it.contains("://") }
            else emptyList()
        }.getOrDefault(emptyList())
        return (cached + builtInTrackers).distinct()
    }

    /** Refreshes the public tracker list once a day (ngosang/trackerslist). */
    fun refreshTrackers(client: OkHttpClient) {
        if (!::baseDir.isInitialized) return
        val f = trackersFile
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24L * 3600 * 1000) return
        runCatching {
            val req = Request.Builder()
                .url("https://cdn.jsdelivr.net/gh/ngosang/trackerslist@master/trackers_best.txt")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val lines = resp.body?.string().orEmpty().lines().map { it.trim() }.filter { it.contains("://") }
                    if (lines.size >= 5) f.writeText(lines.joinToString("\n"))
                }
            }
        }
    }
}
