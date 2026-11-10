@file:OptIn(ExperimentalMaterial3Api::class)

package com.sdm.plus.ui

import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdm.plus.R
import com.sdm.plus.data.Settings
import com.sdm.plus.engine.DownloadEngine
import com.sdm.plus.engine.FileUtils
import com.sdm.plus.engine.MediaSupport
import com.sdm.plus.model.DownloadItem
import com.sdm.plus.model.Kind
import com.sdm.plus.model.Notes
import com.sdm.plus.model.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Screen { MAIN, BROWSER, SETTINGS }

/** Things handed to the app from outside (share, browser "open with", .torrent files, clipboard). */
class Incoming {
    val text = mutableStateOf<String?>(null)
    val torrent = mutableStateOf<ByteArray?>(null)
    val clipboardUrl = mutableStateOf<String?>(null)
}

@Composable
fun AppRoot(engine: DownloadEngine, settings: Settings, incoming: Incoming) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val flow = remember { AddFlow(engine, context, scope, snackbar) }
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var addText by remember { mutableStateOf<String?>(null) }

    // Shared link / "open with SDM+" → add dialog, pre-filled.
    val text = incoming.text.value
    LaunchedEffect(text) {
        if (text != null) {
            screen = Screen.MAIN
            addText = text
            incoming.text.value = null
        }
    }
    // .torrent file opened from the phone → file picker.
    val torrent = incoming.torrent.value
    LaunchedEffect(torrent) {
        if (torrent != null) {
            screen = Screen.MAIN
            flow.openTorrentFile(torrent)
            incoming.torrent.value = null
        }
    }

    when (screen) {
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.MAIN }
            SettingsScreen(settings) { screen = Screen.MAIN }
        }
        Screen.BROWSER -> BrowserScreen(engine, settings, flow, null) { screen = Screen.MAIN }
        Screen.MAIN -> MainScreen(
            engine = engine,
            snackbar = snackbar,
            clipboardUrl = incoming.clipboardUrl,
            flow = flow,
            onAdd = { addText = "" },
            onOpenBrowser = { screen = Screen.BROWSER },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
    }

    addText?.let { initial ->
        AddDialog(
            initial = initial,
            onDismiss = { addText = null },
            onAdd = { urls ->
                addText = null
                flow.submit(urls)
            },
        )
    }
    PrepDialogs(flow, engine)
}

// ============================================================== main list

@Composable
private fun MainScreen(
    engine: DownloadEngine,
    snackbar: SnackbarHostState,
    clipboardUrl: MutableState<String?>,
    flow: AddFlow,
    onAdd: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val all by engine.items.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val clipMsg = stringResource(R.string.clipboard_found)
    val clipAction = stringResource(R.string.download)
    val exportEmpty = stringResource(R.string.export_empty)
    val exportTitle = stringResource(R.string.export_links)

    // Link found in the clipboard → offer to download it.
    val clip = clipboardUrl.value
    LaunchedEffect(clip) {
        if (clip != null) {
            val res = snackbar.showSnackbar(clipMsg, clipAction, withDismissAction = true, duration = SnackbarDuration.Long)
            if (res == SnackbarResult.ActionPerformed) flow.submit(listOf(clip))
            if (clipboardUrl.value == clip) clipboardUrl.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenBrowser) {
                        Icon(Icons.Filled.Public, contentDescription = stringResource(R.string.browser))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.resume_all)) },
                                leadingIcon = { Icon(Icons.Filled.PlayCircle, null) },
                                onClick = { menu = false; engine.resumeAll() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pause_all)) },
                                leadingIcon = { Icon(Icons.Filled.PauseCircle, null) },
                                onClick = { menu = false; engine.pauseAll() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_completed)) },
                                leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                                onClick = { menu = false; engine.clearCompleted() },
                            )
                            DropdownMenuItem(
                                text = { Text(exportTitle) },
                                leadingIcon = { Icon(Icons.Filled.IosShare, null) },
                                onClick = {
                                    menu = false
                                    val links = all.sortedBy { it.createdAt }.joinToString("\n") { it.url }
                                    if (links.isBlank()) {
                                        Toast.makeText(context, exportEmpty, Toast.LENGTH_SHORT).show()
                                    } else {
                                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, links)
                                        context.startActivity(Intent.createChooser(send, exportTitle))
                                    }
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = { menu = false; onOpenSettings() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_download)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val active = all.count { it.status != Status.COMPLETED }
            val done = all.size - active
            val tabs = listOf(
                stringResource(R.string.tab_all) + " (${all.size})",
                stringResource(R.string.tab_active) + " ($active)",
                stringResource(R.string.tab_done) + " ($done)",
            )
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) })
                }
            }
            val totalSpeed = all.filter { it.status == Status.DOWNLOADING }.sumOf { it.speedBps }
            if (totalSpeed > 0) {
                Text(
                    "⬇ " + Formatter.formatShortFileSize(context, totalSpeed) + "/s",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            }
            val shown = when (tab) {
                1 -> all.filter { it.status != Status.COMPLETED }
                2 -> all.filter { it.status == Status.COMPLETED }
                else -> all
            }.sortedByDescending { it.createdAt }

            if (shown.isEmpty()) {
                EmptyState(onOpenBrowser)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { DownloadCard(it, engine) }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onOpenBrowser: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.CloudDownload, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenBrowser) {
            Icon(Icons.Filled.Public, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.browser))
        }
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, engine: DownloadEngine) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var newLink by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val torrentSaved = stringResource(R.string.torrent_files_saved)
    val noVideoMsg = stringResource(R.string.no_video_yet)
    val copiedMsg = stringResource(R.string.link_copied)

    ElevatedCard(
        onClick = {
            if (item.status == Status.COMPLETED) {
                if (item.finalUri != null) FileUtils.open(context, item)
                else Toast.makeText(context, torrentSaved, Toast.LENGTH_LONG).show()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(itemIcon(item), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                if (item.status != Status.COMPLETED && item.status != Status.QUEUED) {
                    if (item.totalBytes > 0 && item.note == null) {
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    } else if (item.status == Status.DOWNLOADING || item.status == Status.CONNECTING || item.status == Status.SAVING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    statusLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == Status.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (item.status) {
                Status.QUEUED, Status.CONNECTING, Status.DOWNLOADING ->
                    IconButton(onClick = { engine.pause(item.id) }) {
                        Icon(Icons.Filled.Pause, stringResource(R.string.pause))
                    }
                Status.PAUSED ->
                    IconButton(onClick = { engine.resume(item.id) }) {
                        Icon(Icons.Filled.PlayArrow, stringResource(R.string.resume))
                    }
                Status.FAILED ->
                    IconButton(onClick = { engine.resume(item.id) }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.retry))
                    }
                else -> {}
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (item.kind == Kind.TORRENT && item.status != Status.COMPLETED) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.play_now)) },
                            leadingIcon = { Icon(Icons.Filled.OndemandVideo, null) },
                            onClick = {
                                menu = false
                                val f = engine.playableFile(item.id)
                                if (f == null) {
                                    Toast.makeText(context, noVideoMsg, Toast.LENGTH_SHORT).show()
                                } else {
                                    if (!item.sequential) engine.setSequential(item.id, true)
                                    runCatching {
                                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                                        val i = Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uri, FileUtils.mimeOf(f.name))
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(i)
                                    }.onFailure { Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show() }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sequential)) },
                            leadingIcon = { Icon(Icons.Filled.FormatListNumbered, null) },
                            trailingIcon = { Checkbox(checked = item.sequential, onCheckedChange = null) },
                            onClick = { menu = false; engine.setSequential(item.id, !item.sequential) },
                        )
                    }
                    if (item.kind != Kind.TORRENT && (item.status == Status.PAUSED || item.status == Status.FAILED)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.change_link)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; newLink = item.url },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_link)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = {
                            menu = false
                            clipboard.setText(AnnotatedString(item.url))
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; confirm = true },
                    )
                }
            }
        }
    }

    newLink?.let { current ->
        var text by remember(item.id) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { newLink = null },
            title = { Text(stringResource(R.string.change_link)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.change_link_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = FileUtils.extractUrls(text).firstOrNull()
                    if (url != null) {
                        engine.changeUrl(item.id, url)
                        engine.resume(item.id)
                    }
                    newLink = null
                }) { Text(stringResource(R.string.resume)) }
            },
            dismissButton = { TextButton(onClick = { newLink = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirm) {
        var alsoFile by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Column {
                    Text(item.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.status == Status.COMPLETED && item.finalUri != null) {
                        Row(
                            Modifier.clickable { alsoFile = !alsoFile },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = alsoFile, onCheckedChange = { alsoFile = it })
                            Text(stringResource(R.string.delete_file_too))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    engine.remove(item.id, alsoFile)
                    confirm = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun statusLine(item: DownloadItem): String {
    val ctx = LocalContext.current
    fun size(b: Long) = Formatter.formatShortFileSize(ctx, b)
    val progress = if (item.totalBytes > 0) {
        "${size(item.downloadedBytes)} / ${size(item.totalBytes)} (${(item.progress * 100).toInt()}%)"
    } else size(item.downloadedBytes)
    val badge = when (item.kind) {
        Kind.TORRENT -> "Torrent • "
        Kind.MEDIA -> (item.formatLabel?.let { choiceLabel(it) } ?: "") +
            (if (item.playlist) " • " + stringResource(R.string.playlist) + (item.playlistPos?.let { " $it" } ?: "") else "") + " • "
        Kind.HTTP -> ""
    }

    val noteText = when (item.note) {
        Notes.WIFI -> stringResource(R.string.note_wifi)
        Notes.CHECKING -> stringResource(R.string.note_check)
        Notes.METADATA -> stringResource(R.string.note_meta)
        Notes.ENGINE -> stringResource(R.string.note_engine)
        else -> null
    }
    if (noteText != null && item.status != Status.COMPLETED && item.status != Status.FAILED) return badge + noteText

    return when (item.status) {
        Status.QUEUED -> badge + stringResource(R.string.status_queued)
        Status.CONNECTING -> badge + stringResource(R.string.status_connecting)
        Status.DOWNLOADING -> buildString {
            append(badge)
            append(progress)
            append(" • ").append(size(item.speedBps)).append("/s")
            if (item.etaSeconds >= 0) append(" • ").append(stringResource(R.string.eta_left, formatEta(item.etaSeconds)))
            if (item.kind == Kind.TORRENT) {
                append(" • ").append(stringResource(R.string.peers_count, item.peers, item.seeds))
            } else if (item.openConnections > 1) {
                append(" • ").append(stringResource(R.string.connections_count, item.openConnections))
            }
        }
        Status.PAUSED -> {
            val warn = if (item.kind == Kind.HTTP && !item.resumable && item.downloadedBytes > 0)
                " • " + stringResource(R.string.not_resumable) else ""
            badge + stringResource(R.string.status_paused) + " • " + progress + warn
        }
        Status.SAVING -> stringResource(R.string.status_saving)
        Status.COMPLETED -> badge + size(item.totalBytes) + " • " + categoryLabel(item.category)
        Status.FAILED -> stringResource(R.string.status_failed, item.error ?: "")
    }
}

private fun formatEta(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    sec >= 60 -> "${sec / 60}m ${sec % 60}s"
    else -> "${sec}s"
}

@Composable
private fun categoryLabel(category: String): String = stringResource(
    when (category) {
        "Video" -> R.string.cat_video
        "Music" -> R.string.cat_music
        "Documents" -> R.string.cat_docs
        "Compressed" -> R.string.cat_archives
        "Programs" -> R.string.cat_apps
        "Images" -> R.string.cat_images
        "Torrents" -> R.string.cat_torrent
        else -> R.string.cat_other
    }
)

@Suppress("DEPRECATION")
private fun itemIcon(item: DownloadItem): ImageVector = when {
    item.kind == Kind.TORRENT -> Icons.Filled.SwapVert
    else -> when (item.category) {
        "Video" -> Icons.Filled.Movie
        "Music" -> Icons.Filled.MusicNote
        "Documents" -> Icons.Filled.Description
        "Compressed" -> Icons.Filled.FolderZip
        "Programs" -> Icons.Filled.Android
        "Images" -> Icons.Filled.Image
        "Torrents" -> Icons.Filled.SwapVert
        else -> Icons.Filled.InsertDriveFile
    }
}

// ============================================================== settings

@Composable
private fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connections by remember { mutableIntStateOf(settings.connections) }
    var parallel by remember { mutableIntStateOf(settings.maxParallel) }
    var speed by remember { mutableStateOf(settings.speedLimitKBps.let { if (it == 0) "" else it.toString() }) }
    var sort by remember { mutableStateOf(settings.sortByType) }
    var clip by remember { mutableStateOf(settings.watchClipboard) }
    var wifi by remember { mutableStateOf(settings.wifiOnly) }
    var torrentWifi by remember { mutableStateOf(settings.torrentWifiOnly) }
    var home by remember { mutableStateOf(settings.homePage) }
    var subs by remember { mutableStateOf(settings.subtitles) }
    var autoResume by remember { mutableStateOf(settings.autoResume) }
    var trackers by remember { mutableStateOf(settings.extraTrackers) }
    var engineVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    val updatedMsg = stringResource(R.string.engine_updated)
    val failedMsg = stringResource(R.string.engine_update_failed)

    LaunchedEffect(Unit) {
        engineVersion = withContext(Dispatchers.IO) { MediaSupport.version(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { p ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(p)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.set_connections, connections), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = connections.toFloat(),
                onValueChange = { connections = it.roundToInt(); settings.connections = connections },
                valueRange = 1f..32f,
                steps = 30,
            )
            Text(
                stringResource(R.string.set_connections_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            Text(stringResource(R.string.set_parallel, parallel), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = parallel.toFloat(),
                onValueChange = { parallel = it.roundToInt(); settings.maxParallel = parallel },
                valueRange = 1f..5f,
                steps = 3,
            )
            HorizontalDivider()

            OutlinedTextField(
                value = speed,
                onValueChange = { v ->
                    val digits = v.filter(Char::isDigit).take(7)
                    speed = digits
                    settings.speedLimitKBps = digits.toIntOrNull() ?: 0
                },
                label = { Text(stringResource(R.string.set_speed)) },
                placeholder = { Text(stringResource(R.string.unlimited)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()

            SwitchRow(stringResource(R.string.set_wifi_only), wifi) { wifi = it; settings.wifiOnly = it }
            SwitchRow(stringResource(R.string.set_torrent_wifi), torrentWifi) { torrentWifi = it; settings.torrentWifiOnly = it }
            SwitchRow(stringResource(R.string.set_auto_resume), autoResume) { autoResume = it; settings.autoResume = it }
            SwitchRow(stringResource(R.string.set_trackers), trackers) { trackers = it; settings.extraTrackers = it }
            SwitchRow(stringResource(R.string.set_subtitles), subs) { subs = it; settings.subtitles = it }
            SwitchRow(stringResource(R.string.set_sort), sort) { sort = it; settings.sortByType = it }
            SwitchRow(stringResource(R.string.set_clipboard), clip) { clip = it; settings.watchClipboard = it }
            HorizontalDivider()

            OutlinedTextField(
                value = home,
                onValueChange = { home = it; if (it.startsWith("http")) settings.homePage = it.trim() },
                label = { Text(stringResource(R.string.set_home)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()

            Text(stringResource(R.string.set_engine), style = MaterialTheme.typography.titleSmall)
            Text(
                "yt-dlp: " + (engineVersion ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = !updating,
                onClick = {
                    updating = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { MediaSupport.updateIfDue(context, settings, force = true) }
                        }
                        updating = false
                        result.onSuccess { v ->
                            engineVersion = v ?: engineVersion
                            Toast.makeText(context, String.format(updatedMsg, v ?: ""), Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(context, failedMsg, Toast.LENGTH_LONG).show()
                        }
                    }
                },
            ) { Text(stringResource(R.string.set_engine_update)) }
            HorizontalDivider()

            Text(
                stringResource(R.string.set_location, "Download/${FileUtils.ROOT_FOLDER}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
