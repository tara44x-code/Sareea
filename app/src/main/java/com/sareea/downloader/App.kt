package com.sareea.downloader

import android.app.Application
import com.sareea.downloader.data.Settings
import com.sareea.downloader.data.Store
import com.sareea.downloader.engine.DownloadEngine
import com.sareea.downloader.service.DownloadService
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
        engine = DownloadEngine(this, Store(File(filesDir, "downloads.json")), settings) {
            DownloadService.ensureRunning(this)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
