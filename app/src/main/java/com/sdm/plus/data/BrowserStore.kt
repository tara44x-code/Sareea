package com.sdm.plus.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class Folder(val id: String, val name: String)

data class Bookmark(val id: String, val title: String, val url: String, val folderId: String, val createdAt: Long)

data class HistoryEntry(val url: String, val title: String, val time: Long)

/** Browser favourites (in folders) and history, saved on the phone only. */
object BrowserStore {
    const val GENERAL = "general"
    private const val MAX_HISTORY = 3000

    private lateinit var bookmarksFile: File
    private lateinit var historyFile: File
    private val writer = Executors.newSingleThreadExecutor()

    private val _folders = MutableStateFlow(listOf(Folder(GENERAL, "")))
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    fun init(ctx: Context) {
        bookmarksFile = File(ctx.filesDir, "bookmarks.json")
        historyFile = File(ctx.filesDir, "history.json")
        writer.execute { load() }
    }

    private fun load() {
        runCatching {
            if (bookmarksFile.exists()) {
                val o = JSONObject(bookmarksFile.readText())
                val fs = o.optJSONArray("folders") ?: JSONArray()
                val folders = (0 until fs.length()).map { i ->
                    val f = fs.getJSONObject(i)
                    Folder(f.getString("id"), f.optString("name"))
                }
                _folders.value = if (folders.none { it.id == GENERAL }) listOf(Folder(GENERAL, "")) + folders else folders
                val bs = o.optJSONArray("bookmarks") ?: JSONArray()
                _bookmarks.value = (0 until bs.length()).map { i ->
                    val b = bs.getJSONObject(i)
                    Bookmark(b.getString("id"), b.optString("title"), b.getString("url"), b.optString("folder", GENERAL), b.optLong("at"))
                }
            }
        }
        runCatching {
            if (historyFile.exists()) {
                val arr = JSONArray(historyFile.readText())
                _history.value = (0 until arr.length()).map { i ->
                    val h = arr.getJSONObject(i)
                    HistoryEntry(h.getString("url"), h.optString("title"), h.optLong("t"))
                }
            }
        }
    }

    private fun saveBookmarks() {
        val folders = _folders.value
        val bookmarks = _bookmarks.value
        writer.execute {
            runCatching {
                val o = JSONObject()
                o.put("folders", JSONArray().apply { folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
                o.put("bookmarks", JSONArray().apply {
                    bookmarks.forEach {
                        put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("folder", it.folderId).put("at", it.createdAt))
                    }
                })
                File(bookmarksFile.path + ".tmp").apply { writeText(o.toString()) }.renameTo(bookmarksFile)
            }
        }
    }

    private fun saveHistory() {
        val list = _history.value
        writer.execute {
            runCatching {
                val arr = JSONArray()
                list.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title).put("t", it.time)) }
                File(historyFile.path + ".tmp").apply { writeText(arr.toString()) }.renameTo(historyFile)
            }
        }
    }

    private fun norm(url: String) = url.trim().removeSuffix("/").substringBefore('#')

    // ------------------------------------------------------------------ bookmarks

    fun bookmarkFor(url: String): Bookmark? = norm(url).let { u -> _bookmarks.value.firstOrNull { norm(it.url) == u } }

    fun addBookmark(title: String, url: String, folderId: String = GENERAL): Bookmark {
        bookmarkFor(url)?.let { return it }
        val b = Bookmark(UUID.randomUUID().toString(), title.ifBlank { url }, url, folderId, System.currentTimeMillis())
        _bookmarks.update { it + b }
        saveBookmarks()
        return b
    }

    fun updateBookmark(b: Bookmark) {
        _bookmarks.update { list -> list.map { if (it.id == b.id) b else it } }
        saveBookmarks()
    }

    fun deleteBookmark(id: String) {
        _bookmarks.update { list -> list.filterNot { it.id == id } }
        saveBookmarks()
    }

    fun addFolder(name: String): Folder {
        val f = Folder(UUID.randomUUID().toString(), name.trim())
        _folders.update { it + f }
        saveBookmarks()
        return f
    }

    fun renameFolder(id: String, name: String) {
        _folders.update { list -> list.map { if (it.id == id) it.copy(name = name.trim()) else it } }
        saveBookmarks()
    }

    /** Deletes a folder; its favourites move to "General". */
    fun deleteFolder(id: String) {
        if (id == GENERAL) return
        _folders.update { list -> list.filterNot { it.id == id } }
        _bookmarks.update { list -> list.map { if (it.folderId == id) it.copy(folderId = GENERAL) else it } }
        saveBookmarks()
    }

    // ------------------------------------------------------------------ history

    fun addHistory(url: String, title: String) {
        if (!url.startsWith("http")) return
        val now = System.currentTimeMillis()
        _history.update { list ->
            val first = list.firstOrNull()
            // Same page again (reloads, single-page apps): just refresh the entry.
            if (first != null && norm(first.url) == norm(url)) listOf(first.copy(title = title.ifBlank { first.title }, time = now)) + list.drop(1)
            else (listOf(HistoryEntry(url, title, now)) + list).take(MAX_HISTORY)
        }
        saveHistory()
    }

    fun deleteHistory(entry: HistoryEntry) {
        _history.update { list -> list.filterNot { it == entry } }
        saveHistory()
    }

    fun clearHistory() {
        _history.value = emptyList()
        saveHistory()
    }
}
