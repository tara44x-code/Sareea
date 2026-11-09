package com.sdm.plus

import android.app.Application
import com.sdm.plus.data.Settings
import com.sdm.plus.data.Store
import com.sdm.plus.engine.DownloadEngine
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
        engine = DownloadEngine(this, Store(File(filesDir, "downloads.json")), settings) {
            DownloadService.ensureRunning(this)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
