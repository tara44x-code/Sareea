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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.filled.CloudDownload
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdm.plus.model.Status
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
import androidx.compose.ui.graphics.Brush
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
import com.sdm.plus.data.Bookmark
import com.sdm.plus.data.BrowserStore
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Checkbox
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.sdm.plus.engine.FilterEngine
import com.sdm.plus.engine.DownloadEngine
import com.sdm.plus.engine.FileUtils
import com.sdm.plus.engine.MediaSupport
import com.sdm.plus.engine.TorrentSupport
import com.sdm.plus.engine.VideoCapture
import android.text.format.Formatter
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MusicNote
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
    val clipboard = LocalClipboardManager.current
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
    var capture by remember { mutableStateOf<Capture?>(null) }
    val media = remember { mutableStateListOf<String>() }
    // Subtitle files (srt/vtt/ass) the page or its player loaded.
    val subs = remember { mutableStateListOf<String>() }
    val mediaReferers = remember { java.util.concurrent.ConcurrentHashMap<String, String>() }
    val blockedCounter = remember { AtomicInteger(0) }
    val bookmarks by BrowserStore.bookmarks.collectAsStateWithLifecycle()
    val folders by BrowserStore.folders.collectAsStateWithLifecycle()
    var showLibrary by remember { mutableStateOf(false) }
    var addingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var desktop by remember { mutableStateOf(appSettings.desktopMode) }
    var webDark by remember { mutableStateOf(appSettings.webDark) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findResult by remember { mutableStateOf("") }
    var textSizeDialog by remember { mutableStateOf(false) }
    val generalName = stringResource(R.string.folder_general)
    val copiedMsg = stringResource(R.string.link_copied)

    val caughtMsg = stringResource(R.string.catch_started)
    val noLinksMsg = stringResource(R.string.grab_none)
    val popupAsk = stringResource(R.string.popup_ask)
    val popupOpen = stringResource(R.string.popup_open)
    val siteOff = stringResource(R.string.adblock_site_off)
    val siteOn = stringResource(R.string.adblock_site_on)
    val currentFlow by rememberUpdatedState(flow)
    val allItems by engine.items.collectAsStateWithLifecycle()

    // Fresh block list once a day, in the background.
    LaunchedEffect(Unit) {
        if (appSettings.adBlock) withContext(Dispatchers.IO) { runCatching { AdBlocker.refreshAll() } }
    }

    fun startCapture() {
        val w = webView ?: return
        capture = Capture.Loading
        val pu = pageUrl
        val title = pageTitle
        val sniffed = media.toList()
        val refs = HashMap(mediaReferers)
        val ua = w.settings.userAgentString
        w.evaluateJavascript(VideoCapture.DOM_JS) { json ->
            val dom = VideoCapture.parseDom(json)
            val frames = VideoCapture.parseIframes(json)
            // The address the page really shows now (sites like YouTube change it without reloading).
            val here = VideoCapture.parsePage(json) ?: pu
            val pageTitleNow = w.title?.takeIf { it.isNotBlank() } ?: title
            if (VideoCapture.isYoutubeListPage(here)) {
                capture = Capture.Message(R.string.capture_youtube_open)
                return@evaluateJavascript
            }
            // Known video sites (YouTube, TikTok, Instagram…): same full quality list as sharing a link.
            if (MediaSupport.isMediaSite(here)) {
                capture = null
                currentFlow.submit(listOf(here), forceMedia = true)
                return@evaluateJavascript
            }
            val cookie = CookieManager.getInstance().getCookie(here)
            scope.launch {
                val list = withContext(Dispatchers.IO) {
                    runCatching { VideoCapture.analyze(context, here, sniffed, dom, cookie, ua, frames, refs) }.getOrDefault(emptyList())
                }
                // Subtitles: files the player loaded + <track> elements on the page.
                val subList = (subs.toList().map { it to "" } + VideoCapture.parseTracks(json))
                    .distinctBy { it.first }
                if (capture == Capture.Loading) capture = Capture.Result(here, pageTitleNow, ua, list, refs, subList)
            }
        }
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
                    // Previous page; only when there is none does it leave the browser.
                    IconButton(onClick = {
                        val w = webView
                        if (w != null && w.canGoBack()) w.goBack() else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
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
                    // Star: add / edit this page in favourites.
                    val saved = bookmarks.firstOrNull { BrowserStore.bookmarkFor(pageUrl)?.id == it.id }
                    IconButton(onClick = {
                        if (pageUrl.startsWith("http")) {
                            addingBookmark = saved ?: BrowserStore.addBookmark(pageTitle.ifBlank { pageUrl }, pageUrl)
                        }
                    }) {
                        Icon(
                            if (saved != null) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.add_favorite),
                            tint = if (saved != null) Neon.Pink else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                            Row(Modifier.padding(horizontal = 8.dp)) {
                                IconButton(enabled = webView?.canGoForward() == true, onClick = { menu = false; webView?.goForward() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.forward))
                                }
                                IconButton(onClick = { menu = false; webView?.reload() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.reload)) }
                                IconButton(onClick = { menu = false; webView?.loadUrl(appSettings.homePage) }) { Icon(Icons.Filled.Home, stringResource(R.string.home)) }
                                IconButton(onClick = { menu = false; showLibrary = true }) { Icon(Icons.Filled.CollectionsBookmark, stringResource(R.string.library)) }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library)) },
                                leadingIcon = { Icon(Icons.Filled.CollectionsBookmark, null) },
                                onClick = { menu = false; showLibrary = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.desktop_mode)) },
                                leadingIcon = { Icon(Icons.Filled.DesktopWindows, null) },
                                trailingIcon = { Checkbox(checked = desktop, onCheckedChange = null) },
                                onClick = {
                                    menu = false
                                    desktop = !desktop
                                    appSettings.desktopMode = desktop
                                    webView?.let { w -> applyUserAgent(w, desktop); w.reload() }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.find_in_page)) },
                                leadingIcon = { Icon(Icons.Filled.Search, null) },
                                onClick = { menu = false; findOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.translate_page)) },
                                leadingIcon = { Icon(Icons.Filled.Translate, null) },
                                onClick = {
                                    menu = false
                                    if (pageUrl.startsWith("http")) {
                                        webView?.loadUrl("https://translate.google.com/translate?sl=auto&tl=ar&u=" + URLEncoder.encode(pageUrl, "UTF-8"))
                                    }
                                },
                            )
                            if (darkSupported) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.web_dark)) },
                                    leadingIcon = { Icon(Icons.Filled.DarkMode, null) },
                                    trailingIcon = { Checkbox(checked = webDark, onCheckedChange = null) },
                                    onClick = {
                                        menu = false
                                        webDark = !webDark
                                        appSettings.webDark = webDark
                                        webView?.let { w -> applyDark(w, webDark) }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.text_size)) },
                                leadingIcon = { Icon(Icons.Filled.FormatSize, null) },
                                onClick = { menu = false; textSizeDialog = true },
                            )
                            HorizontalDivider()
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
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_link)) },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = {
                                    menu = false
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageUrl)
                                    context.startActivity(Intent.createChooser(send, null))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_link)) },
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                onClick = {
                                    menu = false
                                    clipboard.setText(AnnotatedString(pageUrl))
                                    Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_elsewhere)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                onClick = {
                                    menu = false
                                    runCatching {
                                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)).addCategory(Intent.CATEGORY_BROWSABLE)
                                        context.startActivity(Intent.createChooser(i, null))
                                    }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            if (progress in 1..99) NeonProgress(progress / 100f)
            if (findOpen) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = findQuery,
                        onValueChange = { findQuery = it; webView?.findAllAsync(it) },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.find_in_page)) },
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(findResult, style = MaterialTheme.typography.labelMedium, color = Neon.Cyan)
                    IconButton(onClick = { webView?.findNext(false) }) { Icon(Icons.Filled.KeyboardArrowUp, null) }
                    IconButton(onClick = { webView?.findNext(true) }) { Icon(Icons.Filled.KeyboardArrowDown, null) }
                    IconButton(onClick = {
                        findOpen = false; findQuery = ""; findResult = ""; webView?.clearMatches()
                    }) { Icon(Icons.Filled.Close, null) }
                }
            }
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
                        // Sites that work in Chrome but not here: look like Chrome, allow http images/media on https pages.
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.databaseEnabled = true
                        settings.textZoom = appSettings.textZoom
                        applyUserAgent(this, desktop)
                        applyDark(this, webDark)
                        setFindListener { active, count, done ->
                            if (done) findResult = if (count == 0) "0/0" else "${active + 1}/$count"
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val url = request.url.toString()
                                val host = request.url.host
                                // 1) Ads and trackers: answer with nothing.
                                val ph = hostOf(pageUrl)
                                if (!request.isForMainFrame && blockerOn() &&
                                    ((AdBlocker.isAd(host) && !FilterEngine.isAllowed(url, host, ph)) || FilterEngine.shouldBlock(url, host, ph))
                                ) {
                                    val n = blockedCounter.incrementAndGet()
                                    main.post { blockedOnPage = n }
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                }
                                // 2) Subtitles the player loads (film translations).
                                if (isSubtitleUrl(url)) {
                                    (request.requestHeaders["Referer"] ?: request.requestHeaders["referer"])
                                        ?.let { mediaReferers[url] = it }
                                    main.post { if (url !in subs && subs.size < 30) subs.add(url) }
                                }
                                // 3) Media sniffer: remember video/audio the page loads.
                                if (isMediaUrl(url) && !AdBlocker.isAd(host)) {
                                    // Keep the referer the player used: video servers check it.
                                    (request.requestHeaders["Referer"] ?: request.requestHeaders["referer"])
                                        ?.let { mediaReferers[url] = it }
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
                                    // "Open in app" links: use the site's web fallback when it has one.
                                    if (u.startsWith("intent:", true)) {
                                        val intent = runCatching { Intent.parseUri(u, Intent.URI_INTENT_SCHEME) }.getOrNull()
                                        val fallback = intent?.getStringExtra("browser_fallback_url")
                                        if (fallback != null && fallback.startsWith("http")) {
                                            view.loadUrl(fallback)
                                            return true
                                        }
                                        if (request.hasGesture() && intent != null) runCatching {
                                            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                        return true
                                    }
                                    // market://, tel:, mailto: … only when the user actually tapped something.
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
                                if (hostOf(url) != hostOf(pageUrl)) { media.clear(); subs.clear() }
                                address = url
                                pageUrl = url
                                blockedCounter.set(0)
                                blockedOnPage = 0
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                if (url.startsWith("http") && url != pageUrl) {
                                    address = url
                                    pageUrl = url
                                    pageTitle = view.title.orEmpty()
                                    BrowserStore.addHistory(url, pageTitle)
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                address = url
                                pageUrl = url
                                pageTitle = view.title.orEmpty()
                                BrowserStore.addHistory(url, pageTitle)
                                // Desktop mode: also stop pages from forcing a phone-width layout.
                                if (desktop) view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                                if (blockerOn()) {
                                    view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
                                    FilterEngine.cosmeticJs(hostOf(url)).forEach { view.evaluateJavascript(it, null) }
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            // Pop-ups: blocked unless the user tapped; tapped ones open in this tab (ads still blocked).
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
                                            // The user tapped (download buttons often open a second page): open it right away.
                                            else -> view.loadUrl(target)
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
            // Small round "capture video" button; the number = media files the page has loaded.
            if (pageUrl.startsWith("http")) {
                BadgedBox(
                    badge = { if (media.isNotEmpty()) Badge(containerColor = Neon.Cyan, contentColor = Neon.Night) { Text("${media.size}") } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .shadow(14.dp, CircleShape, ambientColor = Neon.Magenta, spotColor = Neon.Magenta)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Neon.Magenta, Neon.Violet)))
                            .clickable { startCapture() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Download, stringResource(R.string.capture_video), tint = Color.White)
                    }
                }
            }
            // Floating button: back to the downloads list (the ← arrow keeps going back in pages).
            val active = allItems.count { it.status == Status.DOWNLOADING || it.status == Status.QUEUED || it.status == Status.CONNECTING }
            BadgedBox(
                badge = { if (active > 0) Badge(containerColor = Neon.Magenta) { Text("$active") } },
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .shadow(14.dp, CircleShape, ambientColor = Neon.Cyan, spotColor = Neon.Violet)
                        .clip(CircleShape)
                        .background(Neon.brush)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CloudDownload, stringResource(R.string.back_to_app), tint = Color.White)
                }
            }
        }
    }

    if (showLibrary) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            LibraryScreen(
                onOpen = { url -> showLibrary = false; webView?.loadUrl(url) },
                onClose = { showLibrary = false },
            )
        }
    }

    addingBookmark?.let { b ->
        BookmarkDialog(
            b, folders, { f -> if (f.id == BrowserStore.GENERAL) generalName else f.name },
            onDismiss = { addingBookmark = null },
            onDelete = { BrowserStore.deleteBookmark(b.id); addingBookmark = null },
        ) { BrowserStore.updateBookmark(it); addingBookmark = null }
    }

    if (textSizeDialog) {
        var zoom by remember { mutableIntStateOf(appSettings.textZoom) }
        AlertDialog(
            onDismissRequest = { textSizeDialog = false },
            title = { Text(stringResource(R.string.text_size) + " $zoom%") },
            text = {
                androidx.compose.material3.Slider(
                    value = zoom.toFloat(),
                    onValueChange = {
                        zoom = (it / 10).toInt() * 10
                        appSettings.textZoom = zoom
                        webView?.settings?.textZoom = zoom
                    },
                    valueRange = 50f..200f,
                )
            },
            confirmButton = { TextButton(onClick = { textSizeDialog = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = {
                TextButton(onClick = { zoom = 100; appSettings.textZoom = 100; webView?.settings?.textZoom = 100 }) {
                    Text(stringResource(R.string.reset))
                }
            },
        )
    }

    when (val c = capture) {
        null -> {}
        Capture.Loading -> AlertDialog(
            onDismissRequest = { capture = null },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), color = Neon.Cyan)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.capture_searching))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { capture = null }) { Text(stringResource(R.string.cancel)) } },
        )
        is Capture.Message -> AlertDialog(
            onDismissRequest = { capture = null },
            title = { Text(stringResource(R.string.capture_title)) },
            text = { Text(stringResource(c.text)) },
            confirmButton = { TextButton(onClick = { capture = null }) { Text(stringResource(R.string.ok)) } },
        )
        is Capture.Result -> CaptureDialog(
            c.found, c.subs,
            onDismiss = { capture = null },
            onPickSub = { (url, label) ->
                capture = null
                val base = safeName(c.title.ifBlank { "subtitle" })
                engine.addHttp(url, subName(base, url, label), CookieManager.getInstance().getCookie(url), c.referers[url] ?: c.pageUrl, c.userAgent)
                Toast.makeText(context, String.format(caughtMsg, subName(base, url, label)), Toast.LENGTH_SHORT).show()
            },
        ) { f, withSubs ->
            capture = null
            val title = c.title.ifBlank { null }
            // Film translation: save the subtitles next to the video, with the same name.
            if (withSubs) {
                val base = safeName(title ?: FileUtils.nameFrom(null, f.url, null).substringBeforeLast('.'))
                c.subs.forEach { (url, label) ->
                    engine.addHttp(url, subName(base, url, label), CookieManager.getInstance().getCookie(url), c.referers[url] ?: c.pageUrl, c.userAgent)
                }
            }
            when (f.source) {
                VideoCapture.Source.PAGE -> {
                    val name = title?.let { t -> safeName(t) + "." + f.ext.lowercase() }
                    engine.addHttp(f.url, name, CookieManager.getInstance().getCookie(f.url), f.referer ?: c.referers[f.url] ?: c.pageUrl, c.userAgent)
                }
                VideoCapture.Source.STREAM -> engine.addMedia(
                    f.url, MediaSupport.BEST, qualityText(f.quality, f.label), title,
                    cookie = CookieManager.getInstance().getCookie(f.url), referer = f.referer ?: c.referers[f.url] ?: c.pageUrl, userAgent = c.userAgent,
                )
                VideoCapture.Source.SITE -> engine.addMedia(
                    f.url, f.ytFormat ?: MediaSupport.BEST, f.label ?: "BEST", title,
                    cookie = CookieManager.getInstance().getCookie(f.url), referer = c.pageUrl, userAgent = c.userAgent,
                )
            }
            Toast.makeText(context, String.format(caughtMsg, title ?: FileUtils.nameFrom(null, f.url, null)), Toast.LENGTH_SHORT).show()
        }
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** Desktop mode: widen the page even when it insists on a phone-sized viewport. */
private const val DESKTOP_VIEWPORT_JS = """
(function(){
  var m = document.querySelector('meta[name=viewport]');
  if (!m) { m = document.createElement('meta'); m.name = 'viewport'; (document.head||document.documentElement).appendChild(m); }
  m.setAttribute('content', 'width=1280');
})()
"""

/**
 * Look like normal Chrome. Some sites hide content or send you to their app when they
 * see an in-app browser ("; wv"), even though they work in Chrome.
 */
private fun applyUserAgent(w: WebView, desktop: Boolean) {
    val s = w.settings
    if (desktop) {
        s.userAgentString = DESKTOP_UA
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
    } else {
        val def = android.webkit.WebSettings.getDefaultUserAgent(w.context)
        s.userAgentString = def.replace("; wv)", ")").replace(Regex("""Version/[\d.]+ """), "")
    }
}

private val darkSupported: Boolean
    get() = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)

/** Dark pages even on sites without a dark theme. */
private fun applyDark(w: WebView, on: Boolean) {
    if (darkSupported) runCatching { androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, on) }
}

private sealed interface Capture {
    data object Loading : Capture
    data class Message(val text: Int) : Capture
    data class Result(
        val pageUrl: String, val title: String, val userAgent: String?,
        val found: List<VideoCapture.Found>, val referers: Map<String, String>,
        val subs: List<Pair<String, String>> = emptyList(), // url to language label
    ) : Capture
}

private fun qualityText(q: Int, label: String?): String = when {
    label != null && q == 0 -> label
    q == Int.MAX_VALUE -> "BEST"
    q < 0 -> "M4A"
    q >= 2160 -> "4K"
    q >= 1440 -> "2K"
    q > 0 -> "${q}p"
    else -> "?"
}

@Composable
private fun CaptureDialog(
    found: List<VideoCapture.Found>,
    subs: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPickSub: (Pair<String, String>) -> Unit,
    onPick: (VideoCapture.Found, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var withSubs by remember { mutableStateOf(subs.isNotEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capture_title)) },
        text = {
            if (found.isEmpty() && subs.isEmpty()) {
                Text(stringResource(R.string.capture_none))
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    if (subs.isNotEmpty() && found.isNotEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { withSubs = !withSubs },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = withSubs, onCheckedChange = { withSubs = it })
                                Text(stringResource(R.string.with_subtitles, subs.size), style = MaterialTheme.typography.titleSmall, color = Neon.Cyan)
                            }
                            HorizontalDivider()
                        }
                    }
                    items(found) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(f, withSubs) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (f.quality < 0 || f.label == "MP3" || f.label == "M4A") Icons.Filled.MusicNote else Icons.Filled.Movie,
                                null, tint = Neon.Pink,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val name = when (f.source) {
                                        VideoCapture.Source.SITE -> choiceLabel(f.label ?: "BEST")
                                        else -> when {
                                            f.quality < 0 -> stringResource(R.string.cat_music)
                                            f.quality == 0 -> f.label ?: stringResource(R.string.quality_unknown)
                                            else -> qualityText(f.quality, f.label)
                                        }
                                    }
                                    Text(name, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        f.ext, style = MaterialTheme.typography.labelSmall, color = Neon.Cyan,
                                        modifier = Modifier
                                            .border(1.dp, Neon.Cyan.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                                Text(
                                    stringResource(
                                        when (f.source) {
                                            VideoCapture.Source.PAGE -> R.string.capture_src_page
                                            VideoCapture.Source.STREAM -> R.string.capture_src_stream
                                            VideoCapture.Source.SITE -> R.string.capture_src_site
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (f.size > 0) (if (f.estimated) "~" else "") + Formatter.formatShortFileSize(ctx, f.size)
                                else stringResource(R.string.size_unknown),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                    if (subs.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.subtitles_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(subs) { sub ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPickSub(sub) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Subtitles, null, tint = Neon.Cyan)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        sub.second.ifBlank { langFromUrl(sub.first) ?: stringResource(R.string.subtitle) },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        FileUtils.nameFrom(null, sub.first, null),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    VideoCapture.extOf(sub.first).uppercase().ifEmpty { "SRT" },
                                    style = MaterialTheme.typography.labelSmall, color = Neon.Cyan,
                                    modifier = Modifier
                                        .border(1.dp, Neon.Cyan.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val subtitleExt = setOf("srt", "vtt", "ass", "ssa", "sub")

private fun isSubtitleUrl(url: String): Boolean =
    url.startsWith("http") && (extOf(url) in subtitleExt || Regex("""[./_-](srt|vtt)(\?|$)""").containsMatchIn(url.lowercase()))

private fun safeName(t: String): String = t.take(80).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "video" }

/** Guess the language from names like movie.ar.srt, arabic.vtt, sub_en.vtt. */
private fun langFromUrl(url: String): String? {
    val n = url.substringBefore('?').substringAfterLast('/').lowercase()
    return when {
        n.contains("arab") || Regex("""[._-]ar[._-]""").containsMatchIn(n) || n.startsWith("ar.") -> "العربية"
        n.contains("engl") || Regex("""[._-]en[._-]""").containsMatchIn(n) || n.startsWith("en.") -> "English"
        else -> null
    }
}

/** movie.ar.srt — same name as the video so players load it automatically. */
private fun subName(base: String, url: String, label: String): String {
    val ext = VideoCapture.extOf(url).takeIf { it in subtitleExt } ?: "srt"
    val lang = when {
        label.isNotBlank() -> label.take(12).replace(Regex("[^\\p{L}\\p{N}]"), "")
        else -> when (langFromUrl(url)) { "العربية" -> "ar"; "English" -> "en"; else -> "" }
    }
    return if (lang.isEmpty()) "$base.$ext" else "$base.$lang.$ext"
}

private val mediaExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "mp3", "m4a", "aac", "ogg", "opus", "flac", "m3u8", "mpd")

private fun extOf(url: String): String =
    url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()

private fun isStreamUrl(url: String): Boolean = extOf(url) == "m3u8" || extOf(url) == "mpd"

private fun isMediaUrl(url: String): Boolean =
    url.startsWith("http") && (extOf(url) in mediaExt || url.contains(".m3u8", true) || url.contains(".mpd?", true))

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
