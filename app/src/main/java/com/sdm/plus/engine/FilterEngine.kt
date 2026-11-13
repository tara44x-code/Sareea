package com.sdm.plus.engine

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ad blocking with the same filter lists uBlock Origin uses (Adblock Plus / uBO syntax):
 *  - network rules:  ||ads.example.com^   /banner/ads*   @@exceptions   $third-party   $domain=…
 *  - cosmetic rules: ##.ad-box   example.com##.sponsor   (hides ad boxes on the page)
 * Not supported (skipped safely): regex rules, scriptlets (+js), procedural cosmetics, redirects, CSP.
 */
object FilterEngine {

    data class FilterList(val id: String, val name: String, val url: String)

    val LISTS = listOf(
        FilterList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt"),
        FilterList("ubo", "uBlock filters", "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssets@master/filters/filters.txt"),
        FilterList("ubo-badware", "uBlock badware risks", "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssets@master/filters/badware.txt"),
        FilterList("liste-ar", "Liste AR", "https://easylist-downloads.adblockplus.org/Liste_AR.txt"),
    )

    private lateinit var dir: File

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "filters").apply { mkdirs() }
    }

    // ------------------------------------------------------------------ rules

    private class Rule(
        val pattern: String,
        val party: Int, // 0 any, 1 third-party only, 2 first-party only
        val include: Set<String>?,
        val exclude: Set<String>?,
    ) {
        private val regex: Regex? by lazy { runCatching { Regex(toRegex(pattern), RegexOption.IGNORE_CASE) }.getOrNull() }

        fun matches(url: String, thirdParty: Boolean, pageHost: String?): Boolean {
            if (party == 1 && !thirdParty) return false
            if (party == 2 && thirdParty) return false
            if (include != null && (pageHost == null || !domainIn(pageHost, include))) return false
            if (exclude != null && pageHost != null && domainIn(pageHost, exclude)) return false
            return regex?.containsMatchIn(url) == true
        }
    }

    private class Index {
        val domains = HashSet<String>()                    // ||example.com^ with no conditions
        val byHost = HashMap<String, MutableList<Rule>>()  // ||example.com/path…
        val byToken = HashMap<String, MutableList<Rule>>() // /ads/banner… keyed by a word in it
        var count = 0
    }

    @Volatile private var block = Index()
    @Volatile private var allow = Index()
    @Volatile private var genericJs: String? = null
    @Volatile private var specific: Map<String, List<String>> = emptyMap()

    @Volatile
    var ruleCount = 0
        private set

    // ------------------------------------------------------------------ download & load

    /** Downloads the lists once a day (or now if [force]) and reloads. Returns the number of rules. */
    fun refresh(force: Boolean = false): Int {
        if (!::dir.isInitialized) return ruleCount
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
        var changed = false
        var failures = 0
        for (l in LISTS) {
            val f = File(dir, "${l.id}.txt")
            if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 24L * 3600 * 1000) continue
            val ok = runCatching {
                client.newCall(Request.Builder().url(l.url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val text = resp.body?.string().orEmpty()
                    if (text.length < 1000) throw java.io.IOException("empty list")
                    File(dir, "${l.id}.tmp").apply { writeText(text) }.renameTo(f)
                }
            }.isSuccess
            if (ok) changed = true else failures++
        }
        if (changed || ruleCount == 0) load()
        if (force && failures == LISTS.size) throw java.io.IOException("Could not download filter lists")
        return ruleCount
    }

    /** Parses the saved lists (call off the main thread). */
    @Synchronized
    fun load() {
        if (!::dir.isInitialized) return
        val b = Index()
        val a = Index()
        val generic = LinkedHashSet<String>()
        val genericExc = HashSet<String>()
        val spec = HashMap<String, MutableList<String>>()
        for (l in LISTS) {
            val f = File(dir, "${l.id}.txt")
            if (!f.exists()) continue
            runCatching { f.forEachLine { parseLine(it, b, a, generic, genericExc, spec) } }
        }
        generic.removeAll(genericExc)
        block = b
        allow = a
        genericJs = if (generic.isEmpty()) null else cssJs(generic.toList(), "sdm-ubo-generic")
        specific = spec
        ruleCount = b.count + a.count + generic.size + spec.values.sumOf { it.size }
    }

    // ------------------------------------------------------------------ matching

    /** Should this request be blocked? [pageHost] = the site you are on. */
    fun shouldBlock(url: String, host: String?, pageHost: String?): Boolean {
        if (host.isNullOrEmpty() || ruleCount == 0) return false
        val h = host.lowercase()
        val third = pageHost == null || registrable(h) != registrable(pageHost)
        if (!matchIndex(block, url, h, third, pageHost)) return false
        return !matchIndex(allow, url, h, third, pageHost)
    }

    /** True if an exception rule allows this request (used to un-block the hosts list too). */
    fun isAllowed(url: String, host: String?, pageHost: String?): Boolean {
        if (host.isNullOrEmpty() || ruleCount == 0) return false
        val h = host.lowercase()
        val third = pageHost == null || registrable(h) != registrable(pageHost)
        return matchIndex(allow, url, h, third, pageHost)
    }

    /** Whole site is an ad/tracker domain (for stopping redirects and pop-ups). */
    fun isBlockedDomain(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var d = host.lowercase()
        val set = block.domains
        while (true) {
            if (d in set) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }

    private fun matchIndex(ix: Index, url: String, host: String, third: Boolean, page: String?): Boolean {
        var d = host
        while (true) {
            if (d in ix.domains) return true
            ix.byHost[d]?.let { list -> if (list.any { it.matches(url, third, page) }) return true }
            val dot = d.indexOf('.')
            if (dot < 0) break
            d = d.substring(dot + 1)
        }
        if (ix.byToken.isEmpty()) return false
        for (m in tokenRe.findAll(url.lowercase())) {
            ix.byToken[m.value]?.let { list -> if (list.any { it.matches(url, third, page) }) return true }
        }
        return false
    }

    /** JavaScript that hides ad boxes on [pageHost] (generic + site-specific rules). */
    fun cosmeticJs(pageHost: String?): List<String> {
        val out = ArrayList<String>(2)
        genericJs?.let { out += it }
        if (pageHost != null) {
            val sels = ArrayList<String>()
            var d = pageHost.lowercase()
            while (true) {
                specific[d]?.let { sels += it }
                val dot = d.indexOf('.')
                if (dot < 0) break
                d = d.substring(dot + 1)
            }
            if (sels.isNotEmpty()) out += cssJs(sels, "sdm-ubo-site")
        }
        return out
    }

    // ------------------------------------------------------------------ parsing

    private val tokenRe = Regex("[a-z0-9%]+")
    private val pureDomainRe = Regex("""^\|\|([a-z0-9.\-]+)\^?\|?$""")
    private val optionCharsRe = Regex("""^[a-z0-9~,=|.\-_*:]+$""")

    private val ignoredTypes = setOf(
        "script", "image", "stylesheet", "css", "xmlhttprequest", "xhr", "subdocument", "frame", "media",
        "object", "ping", "other", "font", "websocket", "popup", "all", "important", "match-case", "beacon",
    )

    private fun parseLine(
        raw: String, b: Index, a: Index,
        generic: MutableSet<String>, genericExc: MutableSet<String>, spec: HashMap<String, MutableList<String>>,
    ) {
        val l = raw.trim()
        if (l.isEmpty() || l[0] == '!' || l[0] == '[') return

        // ---- cosmetic
        if (l.contains("#@#")) {
            val i = l.indexOf("#@#")
            if (i == 0) genericExc += l.substring(3)
            return
        }
        if (l.contains("#?#") || l.contains("#$#") || l.contains("#%#") || l.contains("#@?#") || l.contains("#@$#")) return
        val hi = l.indexOf("##")
        if (hi >= 0) {
            val sel = l.substring(hi + 2)
            if (!validSelector(sel)) return
            val doms = l.substring(0, hi)
            if (doms.isEmpty()) {
                generic += sel
            } else {
                for (d in doms.split(',')) {
                    val dd = d.trim().lowercase()
                    if (dd.isNotEmpty() && !dd.startsWith("~") && !dd.contains('*') && !dd.contains('/')) {
                        spec.getOrPut(dd) { ArrayList() } += sel
                    }
                }
            }
            return
        }

        // ---- network
        var r = l
        val isAllow = r.startsWith("@@")
        if (isAllow) r = r.substring(2)
        if (r.length > 2 && r.startsWith("/") && r.endsWith("/")) return // regex rule
        var party = 0
        var include: MutableSet<String>? = null
        var exclude: MutableSet<String>? = null
        val dollar = r.lastIndexOf('$')
        if (dollar >= 0) {
            val opts = r.substring(dollar + 1)
            if (opts.isNotEmpty() && optionCharsRe.matches(opts)) {
                r = r.substring(0, dollar)
                for (o in opts.split(',')) {
                    when {
                        o == "third-party" || o == "3p" -> party = 1
                        o == "~third-party" || o == "1p" || o == "first-party" || o == "~3p" -> party = 2
                        o.startsWith("domain=") || o.startsWith("from=") -> {
                            for (d in o.substringAfter('=').split('|')) {
                                val dd = d.trim().lowercase()
                                if (dd.isEmpty() || dd.contains('*')) continue
                                if (dd.startsWith("~")) {
                                    val set = exclude ?: HashSet<String>()
                                    set.add(dd.substring(1))
                                    exclude = set
                                } else {
                                    val set = include ?: HashSet<String>()
                                    set.add(dd)
                                    include = set
                                }
                            }
                        }
                        o in ignoredTypes -> {}
                        else -> return // redirect, csp, removeparam, document, negated types… not supported: skip rule
                    }
                }
            }
        }
        if (r.isEmpty() || r == "*" || r == "|" || r == "||") return
        val ix = if (isAllow) a else b

        val pure = pureDomainRe.find(r)
        if (pure != null && party == 0 && include == null && exclude == null) {
            ix.domains += pure.groupValues[1]
            ix.count++
            return
        }
        val rule = Rule(r, party, include, exclude)
        if (r.startsWith("||")) {
            val host = r.substring(2).takeWhile { it.isLetterOrDigit() || it == '.' || it == '-' }.lowercase()
            if (host.contains('.') && !host.endsWith('.')) {
                ix.byHost.getOrPut(host) { ArrayList(1) } += rule
                ix.count++
                return
            }
        }
        val token = bestToken(r.lowercase()) ?: return // too generic to index safely: skip
        ix.byToken.getOrPut(token) { ArrayList(1) } += rule
        ix.count++
    }

    /** A word that must appear as a whole word in any URL this rule matches. */
    private fun bestToken(p: String): String? {
        var best: String? = null
        for (m in tokenRe.findAll(p)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (m.value.length < 3) continue
            val before = if (s > 0) p[s - 1] else null
            val after = if (e < p.length) p[e] else null
            if (before == null || before == '*') continue
            if (after == null || after == '*') continue
            if (m.value in commonTokens) continue
            if (best == null || m.value.length > best.length) best = m.value
        }
        return best
    }

    private val commonTokens = setOf("http", "https", "www", "com", "net", "org", "html", "php", "js")

    private fun validSelector(sel: String): Boolean {
        if (sel.isBlank() || sel.startsWith("^") || sel.startsWith("+js")) return false
        if (sel.contains('{') || sel.contains('}')) return false
        val bad = listOf(
            ":has-text(", ":-abp-", ":xpath(", ":upward(", ":remove(", ":matches-", ":style(", ":watch-attr",
            ":min-text-length", ":others(", ":nth-ancestor", ":remove-attr", ":remove-class", ":contains(",
        )
        return bad.none { sel.contains(it) }
    }

    /** Adblock pattern → regular expression. */
    private fun toRegex(p: String): String {
        val sb = StringBuilder()
        var i = 0
        var s = p
        if (s.startsWith("||")) {
            sb.append("^[a-z][a-z0-9+.\\-]*://(?:[^/?#]*\\.)?")
            s = s.substring(2)
        } else if (s.startsWith("|")) {
            sb.append("^")
            s = s.substring(1)
        }
        val endAnchor = s.endsWith("|")
        if (endAnchor) s = s.dropLast(1)
        while (i < s.length) {
            when (val c = s[i]) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-zA-Z0-9_.%\\-]|$)")
                else -> {
                    if ("\\.+?()[]{}$|/".indexOf(c) >= 0) sb.append('\\')
                    sb.append(c)
                }
            }
            i++
        }
        if (endAnchor) sb.append("$")
        return sb.toString()
    }

    private fun domainIn(host: String, set: Set<String>): Boolean {
        var d = host.lowercase()
        while (true) {
            if (d in set) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }

    /** example.co.uk / ads.example.com → the site part, to tell first- from third-party. */
    private fun registrable(host: String): String {
        val parts = host.lowercase().split('.')
        if (parts.size <= 2) return host.lowercase()
        val n = if (parts[parts.size - 1].length == 2 && parts[parts.size - 2].length <= 3) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    /** Hides [selectors] in groups, so one selector the browser doesn't know can't cancel the rest. */
    private fun cssJs(selectors: List<String>, id: String): String {
        val css = selectors.chunked(50).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }
        return "(function(){if(document.getElementById('$id'))return;var s=document.createElement('style');s.id='$id';" +
            "s.textContent=${JSONObject.quote(css)};(document.head||document.documentElement).appendChild(s);})()"
    }
}
