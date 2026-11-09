@file:OptIn(ExperimentalMaterial3Api::class)

package com.sareea.downloader.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sareea.downloader.R
import com.sareea.downloader.data.Settings
import com.sareea.downloader.engine.DownloadEngine
import com.sareea.downloader.engine.FileUtils
import com.sareea.downloader.model.DownloadItem
import com.sareea.downloader.model.Status
import kotlin.math.roundToInt

@Composable
fun AppRoot(
    engine: DownloadEngine,
    settings: Settings,
    sharedText: MutableState<String?>,
    clipboardUrl: MutableState<String?>,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(settings) { showSettings = false }
    } else {
        MainScreen(engine, sharedText, clipboardUrl) { showSettings = true }
    }
}

// ============================================================== main list

@Composable
private fun MainScreen(
    engine: DownloadEngine,
    sharedText: MutableState<String?>,
    clipboardUrl: MutableState<String?>,
    onOpenSettings: () -> Unit,
) {
    val all by engine.items.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var addText by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val clipMsg = stringResource(R.string.clipboard_found)
    val clipAction = stringResource(R.string.download)

    // Link shared from the browser → open the add dialog with it.
    val incoming = sharedText.value
    LaunchedEffect(incoming) {
        if (incoming != null) {
            addText = incoming
            sharedText.value = null
        }
    }

    // Link found in the clipboard → offer to download it.
    val clip = clipboardUrl.value
    LaunchedEffect(clip) {
        if (clip != null) {
            val res = snackbar.showSnackbar(clipMsg, clipAction, withDismissAction = true, duration = SnackbarDuration.Long)
            if (res == SnackbarResult.ActionPerformed) engine.add(clip)
            if (clipboardUrl.value == clip) clipboardUrl.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addText = "" },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_download)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val tabs = listOf(R.string.tab_all, R.string.tab_active, R.string.tab_done)
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, res ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res)) })
                }
            }
            val shown = when (tab) {
                1 -> all.filter { it.status != Status.COMPLETED }
                2 -> all.filter { it.status == Status.COMPLETED }
                else -> all
            }.sortedByDescending { it.createdAt }

            if (shown.isEmpty()) {
                EmptyState()
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

    addText?.let { initial ->
        AddDialog(
            initial = initial,
            onDismiss = { addText = null },
            onAdd = { urls ->
                engine.addAll(urls)
                addText = null
                tab = 0
            },
        )
    }
}

@Composable
private fun EmptyState() {
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
    }
}

@Composable
private fun DownloadCard(item: DownloadItem, engine: DownloadEngine) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }

    ElevatedCard(
        onClick = { if (item.status == Status.COMPLETED) FileUtils.open(context, item) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(categoryIcon(item.category), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                if (item.status != Status.COMPLETED && item.status != Status.QUEUED) {
                    if (item.totalBytes > 0) {
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
                    maxLines = 2,
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
            IconButton(onClick = { confirm = true }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete))
            }
        }
    }

    if (confirm) {
        var alsoFile by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Column {
                    Text(item.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.status == Status.COMPLETED) {
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

    return when (item.status) {
        Status.QUEUED -> stringResource(R.string.status_queued)
        Status.CONNECTING -> stringResource(R.string.status_connecting)
        Status.DOWNLOADING -> {
            val connections = if (item.openConnections > 1)
                " • " + stringResource(R.string.connections_count, item.openConnections) else ""
            "$progress • ${size(item.speedBps)}/s$connections"
        }
        Status.PAUSED -> {
            val warn = if (!item.resumable && item.downloadedBytes > 0)
                " • " + stringResource(R.string.not_resumable) else ""
            stringResource(R.string.status_paused) + " • " + progress + warn
        }
        Status.SAVING -> stringResource(R.string.status_saving)
        Status.COMPLETED -> size(item.totalBytes) + " • " + categoryLabel(item.category)
        Status.FAILED -> stringResource(R.string.status_failed, item.error ?: "")
    }
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
        else -> R.string.cat_other
    }
)

@Suppress("DEPRECATION")
private fun categoryIcon(category: String): ImageVector = when (category) {
    "Video" -> Icons.Filled.Movie
    "Music" -> Icons.Filled.MusicNote
    "Documents" -> Icons.Filled.Description
    "Compressed" -> Icons.Filled.FolderZip
    "Programs" -> Icons.Filled.Android
    "Images" -> Icons.Filled.Image
    else -> Icons.Filled.InsertDriveFile
}

// ============================================================== add dialog

@Composable
private fun AddDialog(initial: String, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_download)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text(stringResource(R.string.add_hint)) },
                    isError = error,
                    supportingText = if (error) {
                        { Text(stringResource(R.string.no_valid_links)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                TextButton(onClick = {
                    clipboard.getText()?.text?.let { pasted ->
                        text = if (text.isBlank()) pasted else text + "\n" + pasted
                        error = false
                    }
                }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.paste))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val urls = FileUtils.extractUrls(text, lenient = true)
                if (urls.isEmpty()) error = true else onAdd(urls)
            }) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ============================================================== settings

@Composable
private fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    var connections by remember { mutableIntStateOf(settings.connections) }
    var parallel by remember { mutableIntStateOf(settings.maxParallel) }
    var speed by remember { mutableStateOf(settings.speedLimitKBps.let { if (it == 0) "" else it.toString() }) }
    var sort by remember { mutableStateOf(settings.sortByType) }
    var clip by remember { mutableStateOf(settings.watchClipboard) }

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

            SwitchRow(stringResource(R.string.set_sort), sort) { sort = it; settings.sortByType = it }
            SwitchRow(stringResource(R.string.set_clipboard), clip) { clip = it; settings.watchClipboard = it }
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
