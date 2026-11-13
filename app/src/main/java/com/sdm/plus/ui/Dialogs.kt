@file:OptIn(ExperimentalMaterial3Api::class)

package com.sdm.plus.ui

import android.text.format.Formatter
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdm.plus.R
import com.sdm.plus.engine.DownloadEngine
import com.sdm.plus.engine.FileUtils
import com.sdm.plus.engine.MediaSupport

@Composable
fun AddDialog(initial: String, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
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

@Composable
fun PrepDialogs(flow: AddFlow, engine: DownloadEngine) {
    when (val p = flow.prep) {
        null -> {}
        is Prep.Loading -> LoadingDialog(p, flow)
        is Prep.Media -> QualityDialog(p, onDismiss = flow::dismiss) { choice, playlist ->
            engine.addMedia(p.url, choice.format, choice.label, p.meta.title, playlist)
            flow.dismiss()
        }
        is Prep.Torrent -> TorrentFilesDialog(p, onDismiss = flow::dismiss) { selection ->
            engine.addTorrent(p.source, p.bytes, selection, p.meta.name)
            flow.dismiss()
        }
        is Prep.Grab -> GrabDialog(p.urls, onDismiss = flow::dismiss) { chosen ->
            engine.addAuto(chosen)
            flow.dismiss()
        }
    }
}

@Composable
private fun LoadingDialog(p: Prep.Loading, flow: AddFlow) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(stringResource(p.message))
            }
        },
        confirmButton = {
            if (p.url != null) {
                TextButton(onClick = flow::background) { Text(stringResource(R.string.in_background)) }
            }
        },
        dismissButton = {
            TextButton(onClick = flow::dismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun QualityDialog(p: Prep.Media, onDismiss: () -> Unit, onPick: (MediaSupport.Choice, Boolean) -> Unit) {
    val ctx = LocalContext.current
    var wholeList by remember(p) { mutableStateOf(p.playlistOnly) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.meta.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (p.canPlaylist) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !p.playlistOnly) { wholeList = !wholeList },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = wholeList, onCheckedChange = { if (!p.playlistOnly) wholeList = it })
                            Text(stringResource(R.string.whole_playlist), style = MaterialTheme.typography.titleSmall)
                        }
                        HorizontalDivider()
                    }
                }
                items(p.meta.choices) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c, wholeList) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = when (c.label) {
                            "M4A", "MP3" -> Icons.Filled.MusicNote
                            "BEST" -> Icons.Filled.HighQuality
                            else -> Icons.Filled.Videocam
                        }
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(choiceLabel(c.label))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            c.ext,
                            style = MaterialTheme.typography.labelSmall,
                            color = Neon.Cyan,
                            modifier = Modifier
                                .border(1.dp, Neon.Cyan.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (c.sizeEstimate > 0 && !wholeList) "~" + Formatter.formatShortFileSize(ctx, c.sizeEstimate)
                            else if (wholeList) "" else stringResource(R.string.size_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun choiceLabel(label: String): String = when (label) {
    "BEST" -> stringResource(R.string.quality_best)
    "M4A" -> stringResource(R.string.audio_m4a)
    "MP3" -> stringResource(R.string.audio_mp3)
    else -> label
}

@Composable
private fun TorrentFilesDialog(p: Prep.Torrent, onDismiss: () -> Unit, onConfirm: (List<Int>) -> Unit) {
    val ctx = LocalContext.current
    val files = p.meta.files
    val checked = remember(p) { mutableStateListOf(*Array(files.size) { true }) }
    val total = files.filter { checked[it.index] }.sumOf { it.size }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.meta.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val all = checked.all { it }
                        for (i in checked.indices) checked[i] = !all
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked.all { it }, onCheckedChange = { v -> for (i in checked.indices) checked[i] = v })
                    Text(stringResource(R.string.select_all), Modifier.weight(1f))
                    Text(
                        stringResource(R.string.selected_size, Formatter.formatShortFileSize(ctx, total)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(files, key = { it.index }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { checked[f.index] = !checked[f.index] },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked[f.index], onCheckedChange = { checked[f.index] = it })
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(f.path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    Formatter.formatShortFileSize(ctx, f.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = checked.any { it },
                onClick = { onConfirm(files.filter { checked[it.index] }.map { it.index }) },
            ) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun GrabDialog(urls: List<String>, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val checked = remember(urls) { mutableStateListOf(*Array(urls.size) { true }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.grab_title, urls.size)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(urls.indices.toList()) { i ->
                    Row(
                        Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                        Column(Modifier.weight(1f)) {
                            Text(
                                FileUtils.nameFrom(null, urls[i], null),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                urls[i], maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = checked.any { it },
                onClick = { onConfirm(urls.filterIndexed { i, _ -> checked[i] }) },
            ) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
