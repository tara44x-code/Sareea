package com.sdm.plus.model

enum class Status {
    QUEUED, CONNECTING, DOWNLOADING, PAUSED, SAVING, COMPLETED, FAILED;

    val isActive: Boolean
        get() = this == QUEUED || this == CONNECTING || this == DOWNLOADING || this == SAVING
}

/** HTTP = direct file link, TORRENT = magnet / .torrent, MEDIA = video page via yt-dlp. */
enum class Kind { HTTP, TORRENT, MEDIA }

/**
 * One byte range of a file, downloaded over its own connection.
 * [end] is inclusive; -1 means the size is unknown (single stream).
 */
data class Segment(val start: Long, val end: Long, val downloaded: Long) {
    val isDone: Boolean get() = end >= 0 && start + downloaded > end
}

object Notes {
    const val WIFI = "wifi"
    const val CHECKING = "check"
    const val METADATA = "meta"
    const val ENGINE = "engine"
}

data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val category: String,
    val createdAt: Long,
    val kind: Kind = Kind.HTTP,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: Status = Status.QUEUED,
    val speedBps: Long = 0,
    val error: String? = null,
    val resumable: Boolean = false,
    val segments: List<Segment> = emptyList(),
    val finalUri: String? = null,
    // MEDIA: yt-dlp format selector ("mp3:" prefix = convert to mp3) and its label
    val format: String? = null,
    val formatLabel: String? = null,
    // TORRENT: chosen file indexes (empty = all), live peer counts
    val selection: List<Int> = emptyList(),
    val peers: Int = 0,
    val seeds: Int = 0,
    // Captured from the built-in browser so protected downloads still work
    val cookie: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    // Short live state shown under the name (see [Notes])
    val note: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val openConnections: Int
        get() = segments.count { !it.isDone }

    /** Seconds left at the current speed, or -1 if unknown. */
    val etaSeconds: Long
        get() = if (speedBps > 0 && totalBytes > 0) (totalBytes - downloadedBytes).coerceAtLeast(0) / speedBps else -1
}
