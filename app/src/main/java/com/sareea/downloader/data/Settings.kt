package com.sareea.downloader.data

import android.content.Context

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Connections per file (1..32). */
    var connections: Int
        get() = p.getInt("connections", 8)
        set(v) = p.edit().putInt("connections", v.coerceIn(1, 32)).apply()

    /** How many files download at the same time; the rest wait in the queue. */
    var maxParallel: Int
        get() = p.getInt("maxParallel", 3)
        set(v) = p.edit().putInt("maxParallel", v.coerceIn(1, 5)).apply()

    /** Global speed limit in KB/s, 0 = unlimited. */
    var speedLimitKBps: Int
        get() = p.getInt("speedLimit", 0)
        set(v) = p.edit().putInt("speedLimit", v.coerceAtLeast(0)).apply()

    var sortByType: Boolean
        get() = p.getBoolean("sortByType", true)
        set(v) = p.edit().putBoolean("sortByType", v).apply()

    var watchClipboard: Boolean
        get() = p.getBoolean("watchClipboard", true)
        set(v) = p.edit().putBoolean("watchClipboard", v).apply()
}
