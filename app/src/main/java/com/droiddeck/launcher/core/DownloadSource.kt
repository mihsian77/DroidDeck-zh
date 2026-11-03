package com.droiddeck.launcher.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object DownloadSource {
    const val MODE_AUTO = "auto"
    const val MODE_DIRECT = "direct"
    const val MODE_MIRROR = "mirror"
    const val MODE_CUSTOM = "custom"

    private const val PREFS = "download-source"
    private const val KEY_MODE = "mode"
    private const val KEY_MIRROR = "mirror"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_PROXY = "proxy"
    private const val KEY_FASTEST = "fastest"

    private const val PROBE = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck/main/README.md"
    private const val PROBE_TIMEOUT_MS = 4_000
    private const val PROBE_COOLDOWN_MS = 30_000L
    private const val UA = "DroidDeck-Android"

    private val MODES = listOf(MODE_AUTO, MODE_DIRECT, MODE_MIRROR, MODE_CUSTOM)

    private val GITHUB_HOSTS = listOf(
        "github.com",
        "raw.githubusercontent.com",
        "api.github.com",
    )

    private val CATALOGS = listOf(
        "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-CI/",
        "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/",
        "https://raw.githubusercontent.com/Droid-Deck/Drivers-CI/",
    )

    data class Mirror(val host: String, val api: Boolean)

    val MIRRORS = listOf(
        Mirror("gh-proxy.com", true),
        Mirror("gh-proxy.org", true),
        Mirror("ghproxy.net", false),
        Mirror("ghfast.top", false),
    )

    @Volatile
    private var app: Context? = null

    @Volatile
    private var lastGood: String? = null

    private val probing = AtomicBoolean(false)
    private val probedAt = AtomicLong(0L)

    fun init(context: Context) {
        app = context.applicationContext
        lastGood = prefs()?.getString(KEY_FASTEST, null)
    }

    fun probe() {
        if (app == null || prefs()?.getString(KEY_MODE, MODE_AUTO) == MODE_DIRECT) return
        if (!probing.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        if (now - probedAt.get() < PROBE_COOLDOWN_MS) {
            probing.set(false)
            return
        }
        probedAt.set(now)
        Thread {
            try {
                val times = ConcurrentHashMap<String, Long>()
                // 准备所有要测的源：直连 + 普通镜像 + MirrorHub 节点
                val sources = mutableListOf<Pair<String, String>>()
                // 直连
                sources.add("" to PROBE)
                // 普通镜像
                for (m in MIRRORS) {
                    sources.add(m.host to "https://${m.host}/$PROBE")
                }
                // MirrorHub 节点（最多测前 2 个）
                try {
                    val ctx = app
                    if (ctx != null) {
                        val nodes = MirrorHub.getNodes(ctx)
                        for (i in 0 until minOf(2, nodes.size)) {
                            val node = nodes[i]
                            sources.add("mirrorhub:$i" to "https://${node.domain}/$PROBE")
                        }
                    }
                } catch (_: Exception) {}

                val latch = CountDownLatch(sources.size)
                for ((key, url) in sources) {
                    Thread {
                        try {
                            val ms = latency(url)
                            if (ms > 0) times[key] = ms
                        } finally {
                            latch.countDown()
                        }
                    }.apply { isDaemon = true }.start()
                }
                latch.await(PROBE_TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS)
                val best = times.minByOrNull { it.value }?.key ?: return@Thread
                lastGood = best
                prefs()?.edit()?.putString(KEY_FASTEST, best)?.apply()
                Log.i("DownloadSource", "probe done: fastest=$best (${times[best]}ms)")
            } finally {
                probing.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun latency(url: String): Long {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-1023")
                setRequestProperty("User-Agent", UA)
            }
            connection = c
            if (c.responseCode / 100 != 2) 0L else {
                c.inputStream.use { it.read() }
                (System.nanoTime() - started) / 1_000_000
            }
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    fun isGithub(url: String): Boolean = GITHUB_HOSTS.any { url.startsWith("https://$it/") }

    fun isCatalog(url: String): Boolean = CATALOGS.any { url.startsWith(it) }

    fun candidates(target: String): List<String> {
        if (target.startsWith("https://api.github.com/")) return listOf(target)
        val prefs = prefs() ?: return listOf(target)
        if (!isGithub(target)) return listOf(target)
        val api = target.startsWith("https://api.github.com/")
        val proxies = MIRRORS.filter { !api || it.api }.map { it.proxy(target) }
        // MirrorHub dynamic nodes (our nodes)
        val mirrorHubNodes: List<String> = try {
            val ctx = app
            if (ctx != null) {
                val nodes = com.droiddeck.launcher.core.MirrorHub.getNodes(ctx)
                val result = mutableListOf<String>()
                for (i in 0 until minOf(2, nodes.size)) {
                    val node = nodes[i]
                    result.add("https://${node.domain}/$target")
                }
                result
            } else {
                emptyList<String>()
            }
        } catch (_: Exception) {
            emptyList<String>()
        }
        val ordered = when (prefs.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO) {
            MODE_DIRECT -> listOf(target)
            MODE_MIRROR -> listOfNotNull(picked(prefs, api)?.proxy(target)) + mirrorHubNodes + listOf(target) + quickest(proxies)
            MODE_CUSTOM -> listOfNotNull(customProxy(prefs, target)) + listOf(target) + quickest(proxies)
            else -> {
                // AUTO 模式：按测速结果把最快的源排第一
                val result = mutableListOf<String>()
                val lg = lastGood
                when {
                    // 直连最快
                    lg == "" -> {
                        result.add(target)
                        result.addAll(mirrorHubNodes)
                        result.addAll(proxies)
                    }
                    // MirrorHub 节点最快
                    lg?.startsWith("mirrorhub:") == true -> {
                        val idx = lg.removePrefix("mirrorhub:").toIntOrNull() ?: 0
                        // 把对应的 MirrorHub 节点排第一
                        val first = mirrorHubNodes.getOrElse(idx) { mirrorHubNodes.firstOrNull() }
                        if (first != null) result.add(first)
                        // 其他 MirrorHub 节点
                        for (m in mirrorHubNodes) if (m != first) result.add(m)
                        result.add(target)
                        result.addAll(proxies)
                    }
                    // 普通镜像最快
                    lg != null -> {
                        val fastest = proxies.filter { it.startsWith("https://$lg/") }
                        result.addAll(fastest)
                        result.add(target)
                        result.addAll(mirrorHubNodes)
                        // 其他普通镜像
                        for (p in proxies) if (p !in fastest) result.add(p)
                    }
                    // 没测速过，默认直连优先
                    else -> {
                        result.add(target)
                        result.addAll(mirrorHubNodes)
                        result.addAll(proxies)
                    }
                }
                result
            }
        }
        return ordered.distinct()
    }

    private fun quickest(sources: List<String>): List<String> =
        sources.sortedBy { if (host(it) == lastGood) 0 else 1 }

    fun succeeded(source: String) {
        lastGood = host(source)
    }

    fun failed() {
        lastGood = null
        prefs()?.edit()?.remove(KEY_FASTEST)?.apply()
    }

    fun mode(context: Context): String = prefs(context)?.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun setMode(context: Context, mode: String) {
        prefs(context)?.edit()?.putString(KEY_MODE, mode.takeIf { it in MODES } ?: MODE_AUTO)?.apply()
    }

    fun mirror(context: Context): String {
        val host = prefs(context)?.getString(KEY_MIRROR, "") ?: ""
        return MIRRORS.firstOrNull { it.host == host }?.host ?: MIRRORS.first().host
    }

    fun setMirror(context: Context, host: String) {
        prefs(context)?.edit()?.putString(KEY_MIRROR, host.takeIf { h -> MIRRORS.any { it.host == h } } ?: "")?.apply()
    }

    fun custom(context: Context): String = prefs(context)?.getString(KEY_CUSTOM, "") ?: ""

    fun setCustom(context: Context, prefix: String) {
        prefs(context)?.edit()?.putString(KEY_CUSTOM, normalizePrefix(prefix))?.apply()
    }

    fun proxyHostPort(context: Context): String = prefs(context)?.getString(KEY_PROXY, "") ?: ""

    fun setProxy(context: Context, value: String) {
        prefs(context)?.edit()?.putString(KEY_PROXY, normalizeProxy(value))?.apply()
    }

    fun httpProxy(): Proxy? = proxyOf(prefs()?.getString(KEY_PROXY, "") ?: "")

    internal fun proxyOf(value: String): Proxy? {
        val host = value.substringBefore(':')
        val port = value.substringAfter(':', "").toIntOrNull() ?: return null
        if (host.isEmpty()) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    fun reset(context: Context) {
        lastGood = null
        prefs(context)?.edit()?.clear()?.apply()
    }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun prefs(context: Context): SharedPreferences? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Mirror.proxy(target: String): String = "https://$host/$target"

    private fun host(url: String): String =
        MIRRORS.firstOrNull { url.startsWith("https://${it.host}/") }?.host ?: ""

    private fun picked(prefs: SharedPreferences, api: Boolean): Mirror? {
        val chosen = MIRRORS.firstOrNull { it.host == (prefs.getString(KEY_MIRROR, "") ?: "") } ?: MIRRORS.firstOrNull()
        return if (api && chosen != null && !chosen.api) MIRRORS.firstOrNull { it.api } else chosen
    }

    private fun custom(prefs: SharedPreferences): String = prefs.getString(KEY_CUSTOM, "") ?: ""

    private fun customProxy(prefs: SharedPreferences, target: String): String? =
        normalizePrefix(custom(prefs)).takeIf { it.isNotEmpty() }?.let { "$it/$target" }

    private fun normalizePrefix(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.startsWith("http://")) return ""
        return if (trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    internal fun normalizeProxy(value: String): String {
        val trimmed = value.trim().removePrefix("http://").trimEnd('/')
        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':', "").toIntOrNull() ?: 0
        return if (host.isNotEmpty() && port in 1..65535) "$host:$port" else ""
    }
}
