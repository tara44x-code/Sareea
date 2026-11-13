package com.sdm.plus.engine

import android.content.Context
import android.net.Uri
import com.sdm.plus.data.Settings
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat

/** Video/audio from web pages, powered by yt-dlp. */
object MediaSupport {

    /** [ext] is the file type the user gets (MP4, M4A, MP3…). */
    data class Choice(val label: String, val format: String, val sizeEstimate: Long, val ext: String = "MP4")

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
        val duration = info.duration

        // Many sites (Facebook, Instagram…) don't send a file size; estimate it from bitrate × length.
        fun sizeOf(f: VideoFormat, kbps: Int): Long = when {
            f.fileSize > 0 -> f.fileSize
            f.fileSizeApproximate > 0 -> f.fileSizeApproximate
            kbps > 0 && duration > 0 -> kbps.toLong() * 1000 / 8 * duration
            else -> 0L
        }

        val audioOnly = formats.filter { (it.vcodec == null || it.vcodec == "none") && it.acodec != null && it.acodec != "none" }
        val bestAudio = audioOnly.maxByOrNull { if (it.abr > 0) it.abr else it.tbr }
        val bestAudioSize = bestAudio?.let { sizeOf(it, if (it.abr > 0) it.abr else it.tbr) } ?: 0L
        val bestAudioExt = (audioOnly.firstOrNull { it.ext == "m4a" } ?: bestAudio)?.ext?.uppercase() ?: "M4A"

        // Quality = the shorter side, so a vertical 1080×1920 video shows as 1080p (not 1920p).
        val video = formats.filter { it.vcodec != null && it.vcodec != "none" && (it.height > 0 || it.width > 0) }
        fun quality(f: VideoFormat) = if (f.width > 0 && f.height > 0) minOf(f.width, f.height) else maxOf(f.width, f.height)
        val byQuality = video
            .groupBy { quality(it) }
            .mapValues { (_, list) -> list.maxBy { it.tbr } } // best stream of each quality
            .toSortedMap(compareByDescending { it })

        val choices = mutableListOf<Choice>()
        val top = byQuality.values.firstOrNull()
        val topSize = top?.let { f -> sizeOf(f, f.tbr).let { v -> if (v > 0 && hasNoAudio(f)) v + bestAudioSize else v } } ?: 0L
        choices += Choice("BEST", BEST, topSize, "MP4")
        byQuality.forEach { (q, f) ->
            if (q in listOf(2160, 1440, 1080, 720, 480, 360, 240, 144) || byQuality.size <= 6) {
                val id = f.formatId
                // Exact stream picked by id; fall back to "best up to this size" if the id ever disappears.
                val selector = if (id != null) "$id+ba/$id/bv*[height<=$q]+ba/b" else "bv*[height<=$q]+ba/b[height<=$q]"
                val v = sizeOf(f, f.tbr)
                val size = if (v > 0 && hasNoAudio(f)) v + bestAudioSize else v
                choices += Choice(qualityName(q), selector, size, "MP4")
            }
        }
        choices += Choice("M4A", "ba[ext=m4a]/ba/b", bestAudioSize, bestAudioExt.takeIf { it.isNotBlank() } ?: "M4A")
        choices += Choice("MP3", MP3, bestAudioSize, "MP3")

        return Meta(info.title ?: info.fulltitle ?: url, duration, choices)
    }

    private fun hasNoAudio(f: VideoFormat) = f.acodec == null || f.acodec == "none"

    private fun qualityName(q: Int): String = when {
        q >= 2160 -> "4K"
        q >= 1440 -> "2K"
        else -> "${q}p"
    }

    /** Link points to a playlist (alone or together with a video). */
    fun hasPlaylist(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return u.getQueryParameter("list") != null || u.path.orEmpty().contains("/playlist")
    }

    /** Link is only a playlist, with no single video in it. */
    fun isPlaylistOnly(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return hasPlaylist(url) && u.getQueryParameter("v") == null && !url.contains("youtu.be/")
    }

    /** Quality list used for whole playlists (no per-video info needed). */
    fun genericChoices(): List<Choice> = listOf(
        Choice("BEST", BEST, 0, "MP4"),
        Choice("1080p", "bv*[height<=1080]+ba/b[height<=1080]", 0),
        Choice("720p", "bv*[height<=720]+ba/b[height<=720]", 0),
        Choice("480p", "bv*[height<=480]+ba/b[height<=480]", 0),
        Choice("360p", "bv*[height<=360]+ba/b[height<=360]", 0),
        Choice("M4A", "ba[ext=m4a]/ba/b", 0, "M4A"),
        Choice("MP3", MP3, 0, "MP3"),
    )

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }
}
