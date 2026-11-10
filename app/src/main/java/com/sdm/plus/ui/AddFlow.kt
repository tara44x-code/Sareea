package com.sdm.plus.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sdm.plus.R
import com.sdm.plus.engine.DownloadEngine
import com.sdm.plus.engine.MediaSupport
import com.sdm.plus.engine.TorrentSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the add flow is currently showing. */
sealed interface Prep {
    data class Loading(val message: Int, val url: String?) : Prep
    data class Media(val url: String, val meta: MediaSupport.Meta, val canPlaylist: Boolean, val playlistOnly: Boolean) : Prep
    class Torrent(val source: String, val bytes: ByteArray, val meta: TorrentSupport.Meta) : Prep
    data class Grab(val urls: List<String>) : Prep
}

/**
 * Decides what to do with new links:
 * one video link → quality picker, one torrent → file picker, everything else → straight to the queue.
 */
class AddFlow(
    private val engine: DownloadEngine,
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
) {
    var prep by mutableStateOf<Prep?>(null)
        private set

    private var job: Job? = null

    fun submit(urls: List<String>, forceMedia: Boolean = false) {
        if (urls.isEmpty()) return
        if (urls.size > 1) {
            engine.addAuto(urls)
            return
        }
        val u = urls[0]
        when {
            TorrentSupport.isTorrentLink(u) -> load(R.string.loading_torrent, u) {
                val bytes = if (u.startsWith("magnet:", true)) {
                    TorrentSupport.fetchMagnet(u, File(engine.partialDir, "magnet-tmp"), 60)
                } else {
                    engine.fetchBytes(u)
                }
                Prep.Torrent(u, bytes, TorrentSupport.meta(bytes))
            }
            (forceMedia || MediaSupport.isMediaSite(u)) && MediaSupport.isPlaylistOnly(u) -> {
                // Whole playlist: no need to read every video first.
                prep = Prep.Media(u, MediaSupport.Meta(context.getString(R.string.playlist), 0, MediaSupport.genericChoices()), true, true)
            }
            forceMedia || MediaSupport.isMediaSite(u) -> load(R.string.loading_video, u) {
                Prep.Media(u, MediaSupport.fetch(context, u), MediaSupport.hasPlaylist(u), false)
            }
            else -> engine.addAuto(listOf(u))
        }
    }

    /** A .torrent file opened from the phone. */
    fun openTorrentFile(bytes: ByteArray) {
        load(R.string.loading_torrent, null) { Prep.Torrent("file", bytes, TorrentSupport.meta(bytes)) }
    }

    fun grab(urls: List<String>) {
        prep = Prep.Grab(urls)
    }

    /** Skip the picker and let it start in the background with defaults. */
    fun background() {
        val p = prep
        job?.cancel()
        prep = null
        if (p is Prep.Loading && p.url != null) engine.addAuto(listOf(p.url))
    }

    fun dismiss() {
        job?.cancel()
        prep = null
    }

    private fun load(message: Int, url: String?, block: suspend () -> Prep) {
        job?.cancel()
        val loading = Prep.Loading(message, url)
        prep = loading
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                if (prep === loading) prep = result
            } catch (e: Exception) {
                if (prep === loading) {
                    prep = null
                    val msg = (e.message ?: e.javaClass.simpleName).lineSequence()
                        .lastOrNull { it.trim().startsWith("ERROR") } ?: (e.message ?: "")
                    snackbar.showSnackbar(context.getString(R.string.error_prefix, msg.take(160)))
                }
            }
        }
    }
}
