package com.sdm.plus.data

import com.sdm.plus.model.DownloadItem
import com.sdm.plus.model.Kind
import com.sdm.plus.model.Segment
import com.sdm.plus.model.Status
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

    private fun JSONObject.putStr(key: String, v: String?) = put(key, v ?: JSONObject.NULL)
    private fun JSONObject.str(key: String): String? = if (isNull(key) || !has(key)) null else optString(key)

    private fun toJson(i: DownloadItem) = JSONObject().apply {
        put("id", i.id)
        put("url", i.url)
        put("fileName", i.fileName)
        put("category", i.category)
        put("createdAt", i.createdAt)
        put("kind", i.kind.name)
        put("totalBytes", i.totalBytes)
        put("downloadedBytes", i.downloadedBytes)
        put("status", i.status.name)
        putStr("error", i.error)
        put("resumable", i.resumable)
        putStr("finalUri", i.finalUri)
        putStr("format", i.format)
        putStr("formatLabel", i.formatLabel)
        putStr("cookie", i.cookie)
        putStr("referer", i.referer)
        putStr("userAgent", i.userAgent)
        put("selection", JSONArray().apply { i.selection.forEach { put(it) } })
        put("segments", JSONArray().apply {
            i.segments.forEach { s ->
                put(JSONObject().put("s", s.start).put("e", s.end).put("d", s.downloaded))
            }
        })
    }

    private fun fromJson(o: JSONObject): DownloadItem {
        val segs = o.optJSONArray("segments")
        val sel = o.optJSONArray("selection")
        return DownloadItem(
            id = o.getString("id"),
            url = o.getString("url"),
            fileName = o.getString("fileName"),
            category = o.optString("category", "Other"),
            createdAt = o.optLong("createdAt"),
            kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.HTTP),
            totalBytes = o.optLong("totalBytes", -1),
            downloadedBytes = o.optLong("downloadedBytes", 0),
            status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PAUSED),
            error = o.str("error"),
            resumable = o.optBoolean("resumable", false),
            finalUri = o.str("finalUri"),
            format = o.str("format"),
            formatLabel = o.str("formatLabel"),
            cookie = o.str("cookie"),
            referer = o.str("referer"),
            userAgent = o.str("userAgent"),
            selection = if (sel == null) emptyList() else (0 until sel.length()).map { sel.getInt(it) },
            segments = if (segs == null) emptyList() else (0 until segs.length()).map {
                val s = segs.getJSONObject(it)
                Segment(s.getLong("s"), s.getLong("e"), s.getLong("d"))
            },
        )
    }
}
