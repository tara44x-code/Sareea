package com.sdm.plus.ui

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.sdm.plus.App
import com.sdm.plus.engine.FileUtils

class MainActivity : ComponentActivity() {

    private val incoming = Incoming()
    private var lastClipTs = 0L
    private var lastClipUrl: String? = null

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as App

        if (savedInstanceState == null) handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            SDMTheme {
                AppRoot(app.engine, app.settings, incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets apps read the clipboard while they have focus.
        if (hasFocus) checkClipboard()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { incoming.text.value = it }
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.let { incoming.text.value = it.toString() }
            Intent.ACTION_VIEW -> {
                val data: Uri = intent.data ?: return
                when (data.scheme?.lowercase()) {
                    "http", "https", "magnet" -> incoming.text.value = data.toString()
                    "content", "file" -> {
                        // A .torrent file opened from a file manager or another app.
                        val bytes = runCatching {
                            contentResolver.openInputStream(data)?.use { it.readBytes() }
                        }.getOrNull()
                        if (bytes != null && bytes.isNotEmpty()) incoming.torrent.value = bytes
                    }
                }
            }
        }
    }

    private fun checkClipboard() {
        val app = application as App
        if (!app.settings.watchClipboard) return
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val desc = cm.primaryClipDescription ?: return
        // Only read when something new was copied (avoids repeated "pasted" toasts).
        if (desc.timestamp == lastClipTs) return
        lastClipTs = desc.timestamp
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) &&
            !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
        ) return
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) {
            null
        }
        val url = FileUtils.extractUrls(text).firstOrNull()
            ?: text?.trim()?.takeIf { it.startsWith("magnet:", true) }
            ?: return
        if (url == lastClipUrl) return
        lastClipUrl = url
        if (app.engine.items.value.any { it.url == url }) return
        incoming.clipboardUrl.value = url
    }
}
