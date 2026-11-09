package com.sareea.downloader.data

import com.sareea.downloader.model.DownloadItem
import com.sareea.downloader.model.Segment
import com.sareea.downloader.model.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves the download list as JSON so it survives app restarts. */
class Store(private val file: File) {

    @Synchronized
    fun load(): List<DownloadItem> = try {
        if (!file.exists()) emptyList()
        else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(items: List<DownloadItem>) {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(toJson(it)) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    private fun toJson(i: DownloadItem) = JSONObject().apply {
        put("id", i.id)
        put("url", i.url)
        put("fileName", i.fileName)
        put("category", i.category)
        put("createdAt", i.createdAt)
        put("totalBytes", i.totalBytes)
        put("downloadedBytes", i.downloadedBytes)
        put("status", i.status.name)
        put("error", i.error ?: JSONObject.NULL)
        put("resumable", i.resumable)
        put("finalUri", i.finalUri ?: JSONObject.NULL)
        put("segments", JSONArray().apply {
            i.segments.forEach { s ->
                put(JSONObject().put("s", s.start).put("e", s.end).put("d", s.downloaded))
            }
        })
    }

    private fun fromJson(o: JSONObject): DownloadItem {
        val segs = o.optJSONArray("segments")
        return DownloadItem(
            id = o.getString("id"),
            url = o.getString("url"),
            fileName = o.getString("fileName"),
            category = o.optString("category", "Other"),
            createdAt = o.optLong("createdAt"),
            totalBytes = o.optLong("totalBytes", -1),
            downloadedBytes = o.optLong("downloadedBytes", 0),
            status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PAUSED),
            error = if (o.isNull("error")) null else o.optString("error"),
            resumable = o.optBoolean("resumable", false),
            finalUri = if (o.isNull("finalUri")) null else o.optString("finalUri"),
            segments = if (segs == null) emptyList() else (0 until segs.length()).map {
                val s = segs.getJSONObject(it)
                Segment(s.getLong("s"), s.getLong("e"), s.getLong("d"))
            },
        )
    }
}
