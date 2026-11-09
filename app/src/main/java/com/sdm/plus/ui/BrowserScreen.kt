@file:OptIn(ExperimentalMaterial3Api::class)

package com.sdm.plus.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import com.sdm.plus.R
import com.sdm.plus.data.Settings
import com.sdm.plus.engine.DownloadEngine
import com.sdm.plus.engine.FileUtils
import com.sdm.plus.engine.MediaSupport
import com.sdm.plus.engine.TorrentSupport
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Built-in browser. Any download started on a page is caught and sent to SDM+,
 * including pages that need a login (cookies are passed along).
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
    var address by remember { mutableStateOf(startUrl ?: appSettings.homePage) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageUrl by remember { mutableStateOf("") }

    val caughtMsg = stringResource(R.string.catch_started)
    val noLinksMsg = stringResource(R.string.grab_none)
    val currentFlow by rememberUpdatedState(flow)

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
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, null) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
            // Shown on video sites: one tap to pick a quality.
            if (MediaSupport.isMediaSite(pageUrl)) {
                ExtendedFloatingActionButton(
                    onClick = { currentFlow.submit(listOf(pageUrl), forceMedia = true) },
                    icon = { Icon(Icons.Filled.Download, null) },
                    text = { Text(stringResource(R.string.download_video)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
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
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                if (u.startsWith("magnet:", true)) {
                                    currentFlow.submit(listOf(u))
                                    return true
                                }
                                if (!u.startsWith("http")) {
                                    runCatching {
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
                                address = url
                                pageUrl = url
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                address = url
                                pageUrl = url
                                canGoBack = view.canGoBack()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
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
}

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
                    val path = u.substringBefore('?').substringBefore('#')
                    val ext = path.substringAfterLast('/').substringAfterLast('.', "")
                    ext.isNotEmpty() && FileUtils.categoryOf("x.$ext") !in listOf("Other", "Images")
                }
            }
        }
        .distinct()
}
