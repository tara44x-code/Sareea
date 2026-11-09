package com.sareea.downloader.engine

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.sareea.downloader.R
import com.sareea.downloader.model.DownloadItem
import java.io.File
import java.io.IOException
import java.net.URLDecoder

object FileUtils {
    const val ROOT_FOLDER = "Sareea"

    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    private val urlRegex = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /** Picks a file name from the server headers, falling back to the link. */
    fun nameFrom(contentDisposition: String?, url: String, contentType: String?): String {
        var name: String? = null
        if (contentDisposition != null) {
            Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.let { m ->
                    val charset = m.groupValues[1].ifBlank { "UTF-8" }
                    name = runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"'), charset) }.getOrNull()
                }
            if (name.isNullOrBlank()) {
                Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                    .find(contentDisposition)?.let { name = it.groupValues[1].trim() }
            }
        }
        if (name.isNullOrBlank()) {
            name = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        }
        var n = (name ?: "").replace(illegal, "_").trim().trimStart('.')
        if (n.isBlank()) n = "download"
        if (!n.contains('.') && contentType != null) {
            val mime = contentType.substringBefore(';').trim().lowercase()
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n = "$n.$it" }
        }
        if (n.length > 150) {
            val ext = n.substringAfterLast('.', "")
            n = n.take(140) + if (ext.isNotEmpty() && ext.length < 10) ".$ext" else ""
        }
        return n
    }

    fun categoryOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "flv", "ts", "wmv" -> "Video"
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma" -> "Music"
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv", "rtf", "odt" -> "Documents"
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso" -> "Compressed"
        "apk", "apks", "xapk", "exe", "msi", "dmg", "deb" -> "Programs"
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> "Images"
        else -> "Other"
    }

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** Moves the finished file into Download/Sareea/<type>/ so every app can see it. */
    fun publish(context: Context, temp: File, name: String, category: String, sortByType: Boolean): Uri {
        val rel = Environment.DIRECTORY_DOWNLOADS + "/" + ROOT_FOLDER + if (sortByType) "/$category" else ""
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, rel)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create file in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Cannot write file")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    fun viewIntent(item: DownloadItem): Intent? {
        val uri = item.finalUri?.let(Uri::parse) ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeOf(item.fileName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun open(context: Context, item: DownloadItem) {
        val intent = viewIntent(item)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    /** Finds links in any text. With [lenient], "site.com/file.zip" also counts. */
    fun extractUrls(text: String?, lenient: Boolean = false): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val found = urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', ';') }
            .toMutableList()
        if (found.isEmpty() && lenient) {
            text.split(Regex("""\s+"""))
                .filter { it.length > 3 && it.contains('.') && !it.contains('@') }
                .forEach { found += "https://$it" }
        }
        return found.distinct()
    }
}
