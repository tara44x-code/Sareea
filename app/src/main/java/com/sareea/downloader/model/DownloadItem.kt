package com.sareea.downloader.model

enum class Status {
    QUEUED, CONNECTING, DOWNLOADING, PAUSED, SAVING, COMPLETED, FAILED;

    val isActive: Boolean
        get() = this == QUEUED || this == CONNECTING || this == DOWNLOADING || this == SAVING
}

/**
 * One byte range of a file, downloaded over its own connection.
 * [end] is inclusive; -1 means the size is unknown (single stream).
 */
data class Segment(val start: Long, val end: Long, val downloaded: Long) {
    val isDone: Boolean get() = end >= 0 && start + downloaded > end
}

data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val category: String,
    val createdAt: Long,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: Status = Status.QUEUED,
    val speedBps: Long = 0,
    val error: String? = null,
    val resumable: Boolean = false,
    val segments: List<Segment> = emptyList(),
    val finalUri: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val openConnections: Int
        get() = segments.count { !it.isDone }
}
