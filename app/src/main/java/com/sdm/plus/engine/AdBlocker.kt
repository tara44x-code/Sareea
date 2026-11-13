package com.sdm.plus.engine

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ad blocker for the built-in browser.
 *  - Blocks requests to known ad/tracker/popup domains (StevenBlack list, ~80k domains, refreshed daily).
 *  - Works on whole domains and their sub-domains (ads.example.com is caught by example.com).
 *  - A small built-in list keeps working before the first download and offline.
 */
object AdBlocker {

    private val builtIn: Set<String> = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "googletagservices.com", "adnxs.com", "adsrvr.org",
        "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com",
        "openx.net", "amazon-adsystem.com", "moatads.com", "scorecardresearch.com", "zedo.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adsterra.com",
        "adsterra.net", "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "hilltopads.net",
        "clickadu.com", "onclickads.net", "onclkds.com", "admaven.com", "ad-maven.com", "mgid.com",
        "revcontent.com", "adcash.com", "a-ads.com", "popmyads.com", "adskeeper.co.uk", "richpush.co",
        "pushground.com", "monetag.com", "galaksion.com", "trafficstars.com", "adspyglass.com",
        "yllix.com", "ero-advertising.com", "plugrush.com", "admitad.com", "smartadserver.com",
        "yieldmo.com", "media.net", "adform.net", "bidswitch.net", "casalemedia.com", "sharethrough.com",
        "33across.com", "teads.tv", "unrulymedia.com", "inmobi.com", "applovin.com", "unityads.unity3d.com",
    )

    private lateinit var listFile: File

    @Volatile
    private var hosts: Set<String> = builtIn

    val size: Int get() = hosts.size

    fun init(ctx: Context) {
        listFile = File(ctx.filesDir, "adblock-hosts.txt")
    }

    /** Loads the saved list from disk (call off the main thread). */
    fun loadCached() {
        if (!::listFile.isInitialized || !listFile.exists()) return
        runCatching {
            val set = HashSet<String>(120_000)
            listFile.forEachLine { line -> if (line.isNotBlank()) set.add(line.trim()) }
            set.addAll(builtIn)
            if (set.size > builtIn.size) hosts = set
        }
    }

    /** Updates everything: uBlock filter lists + hosts list. Returns total rules. */
    fun refreshAll(force: Boolean = false): Int {
        var error: Throwable? = null
        runCatching { FilterEngine.refresh(force) }.onFailure { error = it }
        runCatching { refresh(force) }.onFailure { if (error != null) throw it }
        if (FilterEngine.ruleCount == 0 && error != null) throw error!!
        return FilterEngine.ruleCount + size
    }

    /** Downloads a fresh hosts list once a day (or now if [force]). Returns the number of domains. */
    fun refresh(force: Boolean = false): Int {
        if (!::listFile.isInitialized) return size
        if (!force && listFile.exists() && System.currentTimeMillis() - listFile.lastModified() < 24L * 3600 * 1000) {
            if (hosts === builtIn) loadCached()
            return size
        }
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val set = HashSet<String>(120_000)
            resp.body?.charStream()?.buffered()?.forEachLine { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                    val host = line.substringAfter(' ').trim().lowercase()
                    if (host.isNotEmpty() && host != "0.0.0.0" && host != "localhost" && host.contains('.')) set.add(host)
                }
            }
            if (set.size < 1000) throw java.io.IOException("List looks broken")
            listFile.writeText(set.joinToString("\n"))
            set.addAll(builtIn)
            hosts = set
        }
        return size
    }

    /** True if [host] or any parent domain is on the list. */
    fun isAd(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        if (FilterEngine.isBlockedDomain(host)) return true
        var h = host.lowercase().removePrefix("www.")
        val set = hosts
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false // stop at "example.com"
            h = h.substring(dot + 1)
        }
    }

    /** Hides common ad boxes that are served from the site itself. */
    const val COSMETIC_JS = """
(function(){
  if (document.getElementById('sdm-adblock-style')) return;
  var css = [
    'ins.adsbygoogle', '.adsbygoogle', '[id^="google_ads_"]', '[id^="div-gpt-ad"]',
    'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication"]', 'iframe[src*="adservice"]',
    '[id*="taboola"]', '[class*="taboola"]', '[id*="outbrain"]', '.OUTBRAIN', '.ad-container', '.ad-slot',
    '.adsbox', '.ad-banner', '.banner-ad', '[aria-label="advertisement"]', '[data-ad-slot]'
  ].join(',') + '{display:none!important;visibility:hidden!important;height:0!important}';
  var s = document.createElement('style'); s.id = 'sdm-adblock-style'; s.textContent = css;
  (document.head || document.documentElement).appendChild(s);
})()
"""

}
