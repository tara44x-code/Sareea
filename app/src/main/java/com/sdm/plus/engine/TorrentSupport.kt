package com.sdm.plus.engine

import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException

/** One shared libtorrent session for the whole app. */
object TorrentSupport {

    data class TFile(val index: Int, val path: String, val size: Long)

    data class Meta(val name: String, val totalSize: Long, val files: List<TFile>)

    @Volatile
    private var sm: SessionManager? = null

    @Synchronized
    fun session(): SessionManager {
        sm?.let { return it }
        val pack = SettingsPack()
            .activeDownloads(10)
            .activeSeeds(10)
        val s = SessionManager(false)
        s.start(SessionParams(pack))
        sm = s
        return s
    }

    fun isTorrentLink(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("magnet:") || u.substringBefore('?').endsWith(".torrent")
    }

    /** Asks the swarm (DHT/trackers) for the torrent's file list. Can take up to [timeoutSec]. */
    fun fetchMagnet(magnet: String, tempDir: File, timeoutSec: Int = 90): ByteArray {
        tempDir.mkdirs()
        return session().fetchMagnet(magnet, timeoutSec, tempDir)
            ?: throw IOException("Could not get torrent info (no peers answered)")
    }

    fun meta(bytes: ByteArray): Meta {
        val ti = TorrentInfo.bdecode(bytes)
        val fs = ti.files()
        val files = (0 until fs.numFiles()).map { i -> TFile(i, fs.filePath(i), fs.fileSize(i)) }
        return Meta(ti.name(), ti.totalSize(), files)
    }
}
