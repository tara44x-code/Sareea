@file:OptIn(ExperimentalMaterial3Api::class)

package com.sdm.plus.ui

import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdm.plus.R
import com.sdm.plus.data.Bookmark
import com.sdm.plus.data.BrowserStore
import com.sdm.plus.data.Folder
import com.sdm.plus.data.HistoryEntry

/** Favourites (organised in folders) and history. */
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val folders by BrowserStore.folders.collectAsStateWithLifecycle()
    val bookmarks by BrowserStore.bookmarks.collectAsStateWithLifecycle()
    val history by BrowserStore.history.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf<String?>(null) } // null = all
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var folderDialog by remember { mutableStateOf<Folder?>(null) } // id "" = new folder
    var confirmClear by remember { mutableStateOf(false) }
    val copiedMsg = stringResource(R.string.link_copied)
    val generalName = stringResource(R.string.folder_general)

    fun nameOf(f: Folder) = if (f.id == BrowserStore.GENERAL) generalName else f.name

    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    if (tab == 0) {
                        IconButton(onClick = { folderDialog = Folder("", "") }) {
                            Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.new_folder))
                        }
                    } else if (history.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteSweep, stringResource(R.string.clear_history))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.favorites) + " (${bookmarks.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.history)) })
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_library)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            val q = query.trim().lowercase()

            if (tab == 0) {
                // Folder chips: All · General · your folders
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = folder == null, onClick = { folder = null },
                        label = { Text(stringResource(R.string.all_folders)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Neon.Violet.copy(alpha = 0.35f)),
                    )
                    folders.forEach { f ->
                        val count = bookmarks.count { it.folderId == f.id }
                        FilterChip(
                            selected = folder == f.id, onClick = { folder = f.id },
                            label = { Text("${nameOf(f)} ($count)") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Neon.Violet.copy(alpha = 0.35f)),
                        )
                    }
                }
                // Selected custom folder: rename / delete
                val selected = folders.firstOrNull { it.id == folder && it.id != BrowserStore.GENERAL }
                if (selected != null) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { folderDialog = selected }) {
                            Icon(Icons.Filled.DriveFileRenameOutline, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.rename))
                        }
                        TextButton(onClick = {
                            BrowserStore.deleteFolder(selected.id)
                            folder = null
                        }) {
                            Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.delete_folder), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                val shown = bookmarks
                    .filter { folder == null || it.folderId == folder }
                    .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
                    .sortedByDescending { it.createdAt }
                if (shown.isEmpty()) {
                    Empty(stringResource(R.string.no_favorites))
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(shown, key = { it.id }) { b ->
                            LinkRow(
                                title = b.title, url = b.url,
                                subtitle = if (folder == null) folders.firstOrNull { it.id == b.folderId }?.let { nameOf(it) } else null,
                                onClick = { onOpen(b.url) },
                            ) { close ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit)) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = { close(); editing = b },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_link)) },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                    onClick = {
                                        close(); clipboard.setText(AnnotatedString(b.url))
                                        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { close(); BrowserStore.deleteBookmark(b.id) },
                                )
                            }
                        }
                    }
                }
            } else {
                val shown = history.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
                if (shown.isEmpty()) {
                    Empty(stringResource(R.string.no_history))
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(shown.take(500)) { h ->
                            LinkRow(
                                title = h.title.ifBlank { h.url }, url = h.url,
                                subtitle = DateUtils.getRelativeTimeSpanString(h.time).toString(),
                                onClick = { onOpen(h.url) },
                            ) { close ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.add_favorite)) },
                                    leadingIcon = { Icon(Icons.Filled.Star, null) },
                                    onClick = { close(); BrowserStore.addBookmark(h.title, h.url) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_link)) },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                    onClick = {
                                        close(); clipboard.setText(AnnotatedString(h.url))
                                        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { close(); BrowserStore.deleteHistory(h) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { b -> BookmarkDialog(b, folders, ::nameOf, onDismiss = { editing = null }) { BrowserStore.updateBookmark(it); editing = null } }

    folderDialog?.let { f ->
        var name by remember(f.id) { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { folderDialog = null },
            title = { Text(stringResource(if (f.id.isEmpty()) R.string.new_folder else R.string.rename)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.folder_name)) })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (f.id.isEmpty()) folder = BrowserStore.addFolder(name).id else BrowserStore.renameFolder(f.id, name)
                    folderDialog = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { folderDialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_history)) },
            confirmButton = {
                TextButton(onClick = { BrowserStore.clearHistory(); confirmClear = false }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Add / edit a favourite: name, link and folder. */
@Composable
fun BookmarkDialog(
    initial: Bookmark,
    folders: List<Folder>,
    nameOf: (Folder) -> String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (Bookmark) -> Unit,
) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var url by remember(initial.id) { mutableStateOf(initial.url) }
    var folderId by remember(initial.id) { mutableStateOf(initial.folderId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_favorite)) },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.link)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.folder), style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(folders, key = { it.id }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { folderId = f.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = folderId == f.id, onClick = { folderId = f.id })
                            Text(nameOf(f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = url.startsWith("http"), onClick = {
                onSave(initial.copy(title = title.ifBlank { url }, url = url.trim(), folderId = folderId))
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.remove_favorite), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun LinkRow(
    title: String,
    url: String,
    subtitle: String?,
    onClick: () -> Unit,
    menuItems: @Composable (close: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val host = remember(url) { Uri.parse(url).host?.removePrefix("www.").orEmpty() }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Letter badge in the icon's colours.
        Box(
            Modifier.size(40.dp).background(Neon.brush, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(host.firstOrNull()?.uppercase() ?: "•", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(host, subtitle).joinToString(" • "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { menuItems { menu = false } }
        }
    }
    HorizontalDivider(Modifier.padding(start = 68.dp))
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
