package com.sdm.plus.engine

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Finds the real video(s) on a browser page and works out quality and size for each:
 *  - <video> elements: their file plus the actual width/height/duration the player reports,
 *  - files the page loaded (mp4, webm, mp3 …): size from the server,
 *  - HLS streams (.m3u8): every quality listed in the stream, size from bitrate × length,
 *  - the site extractor (yt-dlp), for sites it knows.
 */
object VideoCapture {

    enum class Source { PAGE, STREAM, SITE }

    data class Found(
        val quality: Int,          // short side in pixels; 0 = unknown, -1 = audio only
        val ext: String,           // MP4, WEBM, M3U8→MP4, MP3 …
        val size: Long,            // bytes; 0 = unknown
        val estimated: Boolean,    // size from bitrate, not exact
        val url: String,
        val source: Source,
        val ytFormat: String? = null,
        val label: String? = null,
    )

    data class DomVideo(val src: String, val width: Int, val height: Int, val duration: Double)

    /** Reads every <video> on the page: file, real resolution and length. */
    const val DOM_JS = """
(function(){
  var r = [];
  document.querySelectorAll('video').forEach(function(v){
    var srcs = [v.currentSrc || v.src || ''];
    v.querySelectorAll('source[src]').forEach(function(s){ srcs.push(s.src); });
    srcs.forEach(function(s){
      r.push({src: s || '', w: v.videoWidth || 0, h: v.videoHeight || 0, d: isFinite(v.duration) ? v.duration : 0});
    });
  });
  return r;
})()
"""

    fun parseDom(json: String?): List<DomVideo> {
        if (json.isNullOrBlank() || json == "null") return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DomVideo(o.optString("src"), o.optInt("w"), o.optInt("h"), o.optDouble("d", 0.0))
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun analyze(
        ctx: Context,
        pageUrl: String,
        sniffed: List<String>,
        dom: List<DomVideo>,
        cookie: String?,
        userAgent: String?,
    ): List<Found> = coroutineScope {
        val duration = dom.maxOfOrNull { it.duration }?.takeIf { it > 0 } ?: 0.0
        val domByUrl = dom.filter { it.src.startsWith("http") }.associateBy { it.src }
        // Largest playing video size, for files that don't say their resolution.
        val playing = dom.filter { it.width > 0 && it.height > 0 }.maxByOrNull { it.width * it.height }

        val candidates = (sniffed + dom.map { it.src })
            .filter { it.startsWith("http") }
            .distinct()
            .take(25)

        // The site extractor runs alongside (max 25 s).
        val site = async {
            withTimeoutOrNull(25_000) {
                runCatching { MediaSupport.fetch(ctx, pageUrl) }.getOrNull()
            }
        }

        val direct = candidates.map { u ->
            async {
                runCatching {
                    if (isStream(u)) streamQualities(u, cookie, userAgent, pageUrl, duration, playing)
                    else listOfNotNull(fileInfo(u, cookie, userAgent, pageUrl, domByUrl[u], playing))
                }.getOrDefault(emptyList())
            }
        }.flatMap { it.await() }

        val fromSite = site.await()?.choices.orEmpty().map { c ->
            val q = when (c.label) {
                "BEST" -> Int.MAX_VALUE
                "M4A", "MP3" -> -1
                "4K" -> 2160
                "2K" -> 1440
                else -> c.label.removeSuffix("p").toIntOrNull() ?: 0
            }
            Found(q, c.ext, c.sizeEstimate, true, pageUrl, Source.SITE, c.format, c.label)
        }

        (direct.distinctBy { it.url } + fromSite)
            .sortedWith(compareByDescending<Found> { if (it.quality == Int.MAX_VALUE) 100_000 else it.quality }.thenByDescending { it.size })
    }

    private fun isStream(u: String) = extOf(u) in setOf("m3u8", "mpd")

    fun extOf(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun request(u: String, cookie: String?, ua: String?, referer: String): Request.Builder =
        Request.Builder().url(u)
            .header("User-Agent", ua ?: DownloadEngine.UA)
            .header("Referer", referer)
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }

    private val qualityInUrl = Regex("""(?<![0-9])(2160|1440|1080|720|540|480|360|240|144)p?(?![0-9])""")

    /** Size from the server; resolution from the player or the link itself. */
    private fun fileInfo(u: String, cookie: String?, ua: String?, referer: String, dom: DomVideo?, playing: DomVideo?): Found? {
        val req = request(u, cookie, ua, referer).header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val type = resp.header("Content-Type").orEmpty().lowercase()
            if (type.startsWith("text/") || type.contains("json")) return null
            val size = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: resp.body?.contentLength()?.takeIf { it > 1 } ?: 0L
            // Tiny files are thumbnails/previews, not the video.
            if (size in 1..150_000) return null
            val ext = extOf(u).ifEmpty { type.substringAfter('/').substringBefore(';') }.uppercase()
            val audio = type.startsWith("audio/") || ext in setOf("MP3", "M4A", "AAC", "OGG", "OPUS", "FLAC")
            val q = when {
                audio -> -1
                dom != null && dom.width > 0 -> minOf(dom.width, dom.height)
                else -> qualityInUrl.find(u)?.groupValues?.get(1)?.toIntOrNull()
                    ?: playing?.let { minOf(it.width, it.height) } ?: 0
            }
            return Found(q, ext.ifEmpty { "MP4" }, size, false, u, Source.PAGE)
        }
    }

    /** Every quality inside an HLS master playlist, with size from bitrate × length. */
    private fun streamQualities(u: String, cookie: String?, ua: String?, referer: String, domDuration: Double, playing: DomVideo?): List<Found> {
        if (extOf(u) == "mpd") return listOf(Found(playing?.let { minOf(it.width, it.height) } ?: 0, "MP4", 0, true, u, Source.STREAM))
        val text = client.newCall(request(u, cookie, ua, referer).build()).execute().use { it.body?.string().orEmpty() }
        if (!text.startsWith("#EXTM3U")) return emptyList()
        val base = URI(u)
        val lines = text.lines()
        val variants = mutableListOf<Found>()
        var duration = domDuration
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF") }) {
            // Single-quality stream: length from its segments.
            if (duration <= 0) duration = lines.filter { it.startsWith("#EXTINF:") }
                .sumOf { it.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0 }
            val q = playing?.let { minOf(it.width, it.height) } ?: 0
            return listOf(Found(q, "MP4", 0, true, u, Source.STREAM))
        }
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(l)
            val bw = Regex("""(?<!AVERAGE-)BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val q = res?.let { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: 0
            val url = runCatching { base.resolve(next.trim()).toString() }.getOrNull() ?: continue
            variants += Found(q, "MP4", 0, true, url, Source.STREAM, label = if (q == 0 && bw > 0) "${bw / 1000} kbps" else null)
            // Remember bandwidth for the size estimate below.
            bandwidths[url] = bw
        }
        if (duration <= 0 && variants.isNotEmpty()) {
            // Read one quality's playlist to learn the length.
            duration = runCatching {
                client.newCall(request(variants.last().url, cookie, ua, referer).build()).execute().use { r ->
                    r.body?.string().orEmpty().lines().filter { it.startsWith("#EXTINF:") }
                        .sumOf { it.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0 }
                }
            }.getOrDefault(0.0)
        }
        return variants.map { v ->
            val bw = bandwidths.remove(v.url) ?: 0L
            v.copy(size = if (bw > 0 && duration > 0) (bw / 8.0 * duration).toLong() else 0L)
        }.distinctBy { it.quality to it.size }
    }

    private val bandwidths = java.util.concurrent.ConcurrentHashMap<String, Long>()
}
