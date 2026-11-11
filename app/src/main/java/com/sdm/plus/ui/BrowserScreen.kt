@file:OptIn(ExperimentalMaterial3Api::class)

package com.sdm.plus.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sdm.plus.R
import com.sdm.plus.data.Settings
import com.sdm.plus.engine.AdBlocker
import com.sdm.plus.engine.DownloadEngine
import com.sdm.plus.engine.FileUtils
import com.sdm.plus.engine.MediaSupport
import com.sdm.plus.engine.TorrentSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Built-in browser.
 *  - Any download started on a page is caught and sent to SDM+ (cookies included).
 *  - Ad blocker: ad/tracker requests are dropped, ad redirects are stopped, ad boxes are hidden.
 *  - Pop-up control: pop-ups without a tap are blocked; tapped ones to other sites ask first.
 *  - Media sniffer: video/audio files and streams a page loads are listed for download.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    engine: DownloadEngine,
    appSettings: Settings,
    flow: AddFlow,
    startUrl: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val main = remember { Handler(Looper.getMainLooper()) }

    var address by remember { mutableStateOf(startUrl ?: appSettings.homePage) }
    var progress by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var blockedOnPage by remember { mutableIntStateOf(0) }
    var showMedia by remember { mutableStateOf(false) }
    val media = remember { mutableStateListOf<String>() }
    val blockedCounter = remember { AtomicInteger(0) }

    val caughtMsg = stringResource(R.string.catch_started)
    val noLinksMsg = stringResource(R.string.grab_none)
    val popupAsk = stringResource(R.string.popup_ask)
    val popupOpen = stringResource(R.string.popup_open)
    val siteOff = stringResource(R.string.adblock_site_off)
    val siteOn = stringResource(R.string.adblock_site_on)
    val currentFlow by rememberUpdatedState(flow)

    // Fresh block list once a day, in the background.
    LaunchedEffect(Unit) {
        if (appSettings.adBlock) withContext(Dispatchers.IO) { runCatching { AdBlocker.refresh() } }
    }

    fun hostOf(url: String?) = runCatching { Uri.parse(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
    fun blockerOn(): Boolean = appSettings.adBlock && hostOf(pageUrl) !in appSettings.adAllowList

    fun load(input: String) {
        val t = input.trim()
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        webView?.loadUrl(url)
        focus.clearFocus()
    }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                title = {
                    TextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.address_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { load(address) }),
                        trailingIcon = {
                            if (address.isNotEmpty()) IconButton(onClick = { address = "" }) { Icon(Icons.Filled.Close, null) }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                actions = {
                    // Shield: ads blocked on this page; tap to switch the blocker off/on for this site.
                    if (appSettings.adBlock) {
                        val on = hostOf(pageUrl) !in appSettings.adAllowList
                        IconButton(onClick = {
                            val host = hostOf(pageUrl)
                            if (host != null) {
                                val list = appSettings.adAllowList.toMutableSet()
                                val nowOff = if (host in list) { list.remove(host); false } else { list.add(host); true }
                                appSettings.adAllowList = list
                                Toast.makeText(context, String.format(if (nowOff) siteOff else siteOn, host), Toast.LENGTH_SHORT).show()
                                webView?.reload()
                            }
                        }) {
                            BadgedBox(badge = {
                                if (on && blockedOnPage > 0) Badge { Text(if (blockedOnPage > 99) "99+" else "$blockedOnPage") }
                            }) {
                                Icon(Icons.Filled.Shield, null, tint = if (on) Neon.Cyan else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reload)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { menu = false; webView?.reload() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.video_from_page)) },
                                leadingIcon = { Icon(Icons.Filled.VideoLibrary, null) },
                                onClick = {
                                    menu = false
                                    if (pageUrl.startsWith("http")) currentFlow.submit(listOf(pageUrl), forceMedia = true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.grab_links)) },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                                onClick = {
                                    menu = false
                                    webView?.evaluateJavascript(GRAB_JS) { json ->
                                        val urls = parseGrab(json)
                                        if (urls.isEmpty()) Toast.makeText(context, noLinksMsg, Toast.LENGTH_SHORT).show()
                                        else currentFlow.grab(urls)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home)) },
                                leadingIcon = { Icon(Icons.Filled.Home, null) },
                                onClick = { menu = false; webView?.loadUrl(appSettings.homePage) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            when {
                // Video sites: one tap to pick a quality.
                MediaSupport.isMediaSite(pageUrl) -> NeonButton(
                    stringResource(R.string.download_video),
                    Icons.Filled.Download,
                    { currentFlow.submit(listOf(pageUrl), forceMedia = true) },
                )
                // Any other site that played a video/audio file: list what was found.
                media.isNotEmpty() -> NeonButton(
                    stringResource(R.string.media_found, media.size),
                    Icons.Filled.Movie,
                    { showMedia = true },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (progress in 1..99) NeonProgress(progress / 100f)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = !appSettings.popupBlock
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val url = request.url.toString()
                                val host = request.url.host
                                // 1) Ads and trackers: answer with nothing.
                                if (!request.isForMainFrame && blockerOn() && AdBlocker.isAd(host)) {
                                    val n = blockedCounter.incrementAndGet()
                                    main.post { blockedOnPage = n }
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                }
                                // 2) Media sniffer: remember video/audio the page loads.
                                if (isMediaUrl(url) && !AdBlocker.isAd(host)) {
                                    main.post { if (url !in media && media.size < 40) media.add(url) }
                                }
                                return null
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                // Ad redirects that hijack the page.
                                if (blockerOn() && AdBlocker.isAd(request.url.host)) {
                                    blockedOnPage = blockedCounter.incrementAndGet()
                                    return true
                                }
                                if (u.startsWith("magnet:", true)) {
                                    currentFlow.submit(listOf(u))
                                    return true
                                }
                                if (!u.startsWith("http")) {
                                    // intent://, market:// … only when the user actually tapped something.
                                    if (request.hasGesture()) runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                    return true
                                }
                                if (TorrentSupport.isTorrentLink(u)) {
                                    currentFlow.submit(listOf(u))
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (hostOf(url) != hostOf(pageUrl)) media.clear()
                                address = url
                                pageUrl = url
                                blockedCounter.set(0)
                                blockedOnPage = 0
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                address = url
                                pageUrl = url
                                pageTitle = view.title.orEmpty()
                                if (blockerOn()) view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            // Pop-ups: blocked unless the user tapped; tapped ones to other sites ask first.
                            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                if (appSettings.popupBlock && !isUserGesture) {
                                    blockedOnPage = blockedCounter.incrementAndGet()
                                    return false
                                }
                                // Catch the address the pop-up wants to open, without showing it.
                                val probe = WebView(ctx)
                                probe.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                                        val target = r.url.toString()
                                        v.stopLoading()
                                        main.post { v.destroy() }
                                        when {
                                            blockerOn() && AdBlocker.isAd(r.url.host) ->
                                                blockedOnPage = blockedCounter.incrementAndGet()
                                            !appSettings.popupBlock || hostOf(target) == hostOf(pageUrl) ->
                                                view.loadUrl(target)
                                            else -> scope.launch {
                                                val res = snackbar.showSnackbar(
                                                    String.format(popupAsk, hostOf(target) ?: target),
                                                    popupOpen, withDismissAction = true, duration = SnackbarDuration.Long,
                                                )
                                                if (res == SnackbarResult.ActionPerformed) view.loadUrl(target)
                                            }
                                        }
                                        return true
                                    }
                                }
                                (resultMsg.obj as WebView.WebViewTransport).webView = probe
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        // The heart of it: any download on any page comes to SDM+.
                        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            if (!url.startsWith("http")) {
                                Toast.makeText(ctx, R.string.cannot_catch, Toast.LENGTH_SHORT).show()
                                return@setDownloadListener
                            }
                            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                            if (name.endsWith(".torrent", true)) {
                                currentFlow.submit(listOf(url))
                                return@setDownloadListener
                            }
                            engine.addHttp(
                                url = url,
                                fileName = name,
                                cookie = CookieManager.getInstance().getCookie(url),
                                referer = this.url,
                                userAgent = userAgent,
                            )
                            Toast.makeText(ctx, String.format(caughtMsg, name), Toast.LENGTH_SHORT).show()
                        }
                        loadUrl(address)
                        webView = this
                    }
                },
            )
        }
    }

    if (showMedia) {
        MediaFoundDialog(
            urls = media.toList(),
            onDismiss = { showMedia = false },
            onPick = { url ->
                showMedia = false
                val ua = webView?.settings?.userAgentString
                if (isStreamUrl(url)) {
                    // HLS/DASH streams are stitched together by yt-dlp + ffmpeg.
                    engine.addMedia(url, MediaSupport.BEST, "BEST", pageTitle.ifBlank { null })
                } else {
                    engine.addHttp(url, null, CookieManager.getInstance().getCookie(url), pageUrl, ua)
                }
                Toast.makeText(context, String.format(caughtMsg, FileUtils.nameFrom(null, url, null)), Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun MediaFoundDialog(urls: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_found, urls.size)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(urls) { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(u) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Movie, null, tint = Neon.Pink)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (isStreamUrl(u)) "▶ Stream • " else "") + FileUtils.nameFrom(null, u, null),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                Uri.parse(u).host.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Filled.Download, null, tint = Neon.Cyan)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val mediaExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "mp3", "m4a", "aac", "ogg", "opus", "flac", "m3u8", "mpd")

private fun extOf(url: String): String =
    url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()

private fun isStreamUrl(url: String): Boolean = extOf(url) == "m3u8" || extOf(url) == "mpd"

private fun isMediaUrl(url: String): Boolean = url.startsWith("http") && extOf(url) in mediaExt

private const val GRAB_JS = """
(function(){
  var s = new Set();
  document.querySelectorAll('a[href]').forEach(function(a){ s.add(a.href); });
  document.querySelectorAll('video[src],audio[src],source[src]').forEach(function(v){ s.add(v.src); });
  return Array.from(s);
})()
"""

private fun parseGrab(json: String?): List<String> {
    if (json.isNullOrBlank() || json == "null") return emptyList()
    val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return (0 until arr.length())
        .map { arr.optString(it) }
        .filter { u ->
            when {
                u.startsWith("magnet:", true) -> true
                !u.startsWith("http") -> false
                TorrentSupport.isTorrentLink(u) -> true
                else -> {
                    val ext = extOf(u)
                    ext.isNotEmpty() && FileUtils.categoryOf("x.$ext") !in listOf("Other", "Images")
                }
            }
        }
        .distinct()
}
