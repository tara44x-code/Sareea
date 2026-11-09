package com.sareea.downloader.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sareea.downloader.App
import com.sareea.downloader.model.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while files download (even with the screen off)
 * and shows the progress notification. Stops itself when nothing is active.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notified = HashSet<String>()
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Notifications.createChannels(this)
        goForeground()

        val engine = App.instance.engine
        engine.items.value.filter { it.status == Status.COMPLETED }.forEach { notified += it.id }

        scope.launch {
            engine.items.collect { list ->
                list.filter { it.status == Status.COMPLETED && notified.add(it.id) }
                    .forEach { Notifications.completed(this@DownloadService, it) }

                val active = list.filter { it.status.isActive }
                if (active.isEmpty()) {
                    stopNow()
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotify > 900) {
                        lastNotify = now
                        Notifications.notify(
                            this@DownloadService,
                            Notifications.PROGRESS_ID,
                            Notifications.progress(this@DownloadService, active),
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        goForeground()
        if (intent?.action == ACTION_PAUSE_ALL) App.instance.engine.pauseAll()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val active = App.instance.engine.items.value.filter { it.status.isActive }
        ServiceCompat.startForeground(
            this,
            Notifications.PROGRESS_ID,
            Notifications.progress(this, active),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun stopNow() {
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.sareea.downloader.PAUSE_ALL"

        @Volatile
        private var isRunning = false

        fun ensureRunning(ctx: Context) {
            if (isRunning) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            } catch (_: Exception) {
                // Android may refuse when the app is in the background; downloads still continue.
            }
        }
    }
}
