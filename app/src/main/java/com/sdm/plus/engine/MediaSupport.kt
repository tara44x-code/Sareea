package com.sdm.plus.engine

import android.content.Context
import android.net.Uri
import com.sdm.plus.data.Settings
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

/** Video/audio from web pages, powered by yt-dlp. */
object MediaSupport {

    data class Choice(val label: String, val format: String, val sizeEstimate: Long)

    data class Meta(val title: String, val durationSec: Int, val choices: List<Choice>)

    const val BEST = "bv*+ba/b"
    const val MP3 = "mp3:ba/b"

    private val lock = Any()

    @Volatile
    private var ready = false

    /** Unpacks python + ffmpeg the first time (a few seconds). */
    fun ensureInit(ctx: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
            ready = true
        }
    }

    /** Sites change often; keep yt-dlp fresh (at most once a day unless [force]). */
    fun updateIfDue(ctx: Context, settings: Settings, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force && now - settings.lastEngineUpdate < 24L * 3600 * 1000) return null
        ensureInit(ctx)
        return try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            settings.lastEngineUpdate = now
            YoutubeDL.getInstance().versionName(ctx.applicationContext)
        } catch (e: Exception) {
            if (force) throw e else null
        }
    }

    fun version(ctx: Context): String? = runCatching { YoutubeDL.getInstance().versionName(ctx.applicationContext) }.getOrNull()

    private val mediaHosts = listOf(
        "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com", "fb.watch",
        "twitter.com", "x.com", "vimeo.com", "dailymotion.com", "dai.ly", "reddit.com", "redd.it",
        "twitch.tv", "soundcloud.com", "pinterest.com", "pin.it", "snapchat.com", "threads.net",
        "bilibili.com", "rumble.com", "streamable.com", "likee.video", "kwai.com", "aparat.com",
        "ok.ru", "vk.com", "bandcamp.com", "mixcloud.com", "9gag.com", "imgur.com", "telegram.org", "t.me",
    )

    fun isMediaSite(url: String): Boolean {
        val host = runCatching { Uri.parse(url.trim()).host?.lowercase() }.getOrNull() ?: return false
        return mediaHosts.any { host == it || host.endsWith(".$it") }
    }

    /** Reads the page and builds the quality list (1080p, 720p, audio…). */
    fun fetch(ctx: Context, url: String): Meta {
        ensureInit(ctx)
        val req = YoutubeDLRequest(url).addOption("--no-playlist")
        val info = YoutubeDL.getInstance().getInfo(req)
        val formats = info.formats.orEmpty()

        val bestAudio = formats
            .filter { (it.vcodec == null || it.vcodec == "none") && it.acodec != null && it.acodec != "none" }
            .maxOfOrNull { sizeOf(it.fileSize, it.fileSizeApproximate) } ?: 0L

        val heights = formats
            .filter { it.vcodec != null && it.vcodec != "none" && it.height > 0 }
            .groupBy { it.height }
            .mapValues { (_, list) -> list.maxOf { sizeOf(it.fileSize, it.fileSizeApproximate) } }
            .toSortedMap(compareByDescending { it })

        val choices = mutableListOf<Choice>()
        val bestSize = heights.values.firstOrNull()?.let { if (it > 0) it + bestAudio else 0L } ?: 0L
        choices += Choice("BEST", BEST, bestSize)
        heights.forEach { (h, size) ->
            if (h in listOf(2160, 1440, 1080, 720, 480, 360, 240, 144) || heights.size <= 6) {
                choices += Choice("${h}p", "bv*[height<=$h]+ba/b[height<=$h]", if (size > 0) size + bestAudio else 0L)
            }
        }
        choices += Choice("M4A", "ba[ext=m4a]/ba/b", bestAudio)
        choices += Choice("MP3", MP3, bestAudio)

        return Meta(info.title ?: info.fulltitle ?: url, info.duration, choices)
    }

    private fun sizeOf(a: Long, b: Long) = if (a > 0) a else b

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }
}
