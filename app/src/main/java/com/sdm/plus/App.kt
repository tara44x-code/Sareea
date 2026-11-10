package com.sdm.plus

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.sdm.plus.data.Settings
import com.sdm.plus.data.Store
import com.sdm.plus.engine.DownloadEngine
import com.sdm.plus.engine.TorrentSupport
import com.sdm.plus.service.DownloadService
import java.io.File

class App : Application() {
    lateinit var settings: Settings
        private set
    lateinit var engine: DownloadEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        TorrentSupport.init(this)
        engine = DownloadEngine(this, Store(File(filesDir, "downloads.json")), settings) {
            DownloadService.ensureRunning(this)
        }
        watchNetwork()
    }

    /** When the internet comes back, failed downloads continue on their own. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val main = Handler(Looper.getMainLooper())
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Give the connection a few seconds to settle first.
                    main.postDelayed({ engine.onNetworkBack() }, 4000)
                }
            })
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
