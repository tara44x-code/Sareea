package com.sdm.plus.engine

import kotlinx.coroutines.delay

/** Shared across every connection so the limit applies to total speed. */
class SpeedLimiter(private val limitKBps: () -> Int) {
    private var windowStart = System.nanoTime()
    private var bytesInWindow = 0L

    suspend fun acquire(bytes: Int) {
        val limit = limitKBps()
        if (limit <= 0) return
        val bytesPerSec = limit * 1024L
        val waitMs: Long
        synchronized(this) {
            val now = System.nanoTime()
            if (now - windowStart > 1_000_000_000L) {
                windowStart = now
                bytesInWindow = 0
            }
            bytesInWindow += bytes
            val expectedNs = bytesInWindow * 1_000_000_000L / bytesPerSec
            waitMs = (expectedNs - (now - windowStart)) / 1_000_000L
        }
        if (waitMs > 0) delay(waitMs)
    }
}
