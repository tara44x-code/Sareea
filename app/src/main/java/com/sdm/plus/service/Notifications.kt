package com.sdm.plus.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sdm.plus.R
import com.sdm.plus.engine.FileUtils
import com.sdm.plus.model.DownloadItem
import com.sdm.plus.ui.MainActivity

object Notifications {
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"
    const val PROGRESS_ID = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, ctx.getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, ctx.getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(ctx: Context, active: List<DownloadItem>): Notification {
        val sized = active.filter { it.totalBytes > 0 }
        val total = sized.sumOf { it.totalBytes }
        val done = sized.sumOf { it.downloadedBytes }
        val speed = active.sumOf { it.speedBps }
        val pauseAll = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(ctx.getString(R.string.notif_downloading, active.size))
            .setContentText(active.firstOrNull()?.fileName ?: "")
            .setSubText(Formatter.formatShortFileSize(ctx, speed) + "/s")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, ctx.getString(R.string.pause_all), pauseAll)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (total > 0) b.setProgress(100, (done * 100 / total).toInt(), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    fun completed(ctx: Context, item: DownloadItem) {
        val view = FileUtils.viewIntent(item) ?: return
        val pi = PendingIntent.getActivity(
            ctx, item.id.hashCode(), view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(ctx.getString(R.string.notif_done))
            .setContentText(item.fileName)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        notify(ctx, item.id.hashCode(), n)
    }

    fun notify(ctx: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
