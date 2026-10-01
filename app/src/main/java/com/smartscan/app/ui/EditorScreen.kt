package com.smartscan.app.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.smartscan.app.App
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.FilterType
import com.smartscan.app.processing.ImageUtils
import com.smartscan.app.processing.OcrEngine
import com.smartscan.app.processing.ScanActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(docId: String, onBack: () -> Unit, onCrop: (String) -> Unit, onAddPages: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val docs by DocumentRepository.documents.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.id == docId }
    if (doc == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val pages = doc.pages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val index = pagerState.currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val current = pages.getOrNull(index)

    var busy by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    var confirmDeleteDoc by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    // Runs in the app scope so a render is never left half-done if the user navigates away.
    fun runWork(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        App.scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e("SmartScan", "Edit failed", e)
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { showRename = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showExport = true }, enabled = pages.isNotEmpty() && !busy) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename document") },
                                onClick = { menuOpen = false; showRename = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Move page left") },
                                enabled = current != null && index > 0,
                                onClick = {
                                    menuOpen = false
                                    val p = current ?: return@DropdownMenuItem
                                    scope.launch {
                                        ScanActions.movePage(docId, p.id, -1)
                                        pagerState.animateScrollToPage(index - 1)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Move page right") },
                                enabled = current != null && index < pages.size - 1,
                                onClick = {
                                    menuOpen = false
                                    val p = current ?: return@DropdownMenuItem
                                    scope.launch {
                                        ScanActions.movePage(docId, p.id, 1)
                                        pagerState.animateScrollToPage(index + 1)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Apply this filter to all pages") },
                                enabled = current != null && pages.size > 1,
                                onClick = {
                                    menuOpen = false
                                    val f = current?.filter ?: return@DropdownMenuItem
                                    runWork { ScanActions.applyFilterToAll(docId, f) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete document") },
                                onClick = { menuOpen = false; confirmDeleteDoc = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolButton(Icons.Default.Crop, "Crop", enabled = current != null && !busy) {
                        current?.let { onCrop(it.id) }
                    }
                    ToolButton(Icons.Default.RotateRight, "Rotate", enabled = current != null && !busy) {
                        val p = current ?: return@ToolButton
                        runWork { ScanActions.editPage(docId, p.id) { it.copy(rotation = (it.rotation + 90) % 360) } }
                    }
                    ToolButton(Icons.Default.AddAPhoto, "Add", enabled = !busy) { onAddPages() }
                    ToolButton(Icons.Default.TextFields, "Text", enabled = current != null && !busy) {
                        val p = current ?: return@ToolButton
                        runWork {
                            val bmp = withContext(Dispatchers.Default) {
                                ImageUtils.load(DocumentRepository.file(docId, p.processedFile), 2500)
                            }
                            val result = OcrEngine.recognize(bmp)
                            bmp.recycle()
                            withContext(Dispatchers.Main) {
                                ocrText = result.text.ifBlank { "No text found on this page." }
                            }
                        }
                    }
                    ToolButton(Icons.Default.Delete, "Delete", enabled = current != null && !busy) {
                        confirmDeletePage = true
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (pages.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("This document has no pages")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAddPages) { Text("Scan pages") }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        pageSpacing = 16.dp,
                        key = { i -> pages.getOrNull(i)?.id ?: i },
                    ) { i ->
                        val page = pages.getOrNull(i)
                        if (page != null) {
                            AsyncImage(
                                model = DocumentRepository.file(docId, page.processedFile),
                                contentDescription = "Page ${i + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    Text(
                        "${index + 1} / ${pages.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(FilterType.entries) { f ->
                        FilterChip(
                            selected = current?.filter == f,
                            enabled = !busy,
                            onClick = {
                                val p = current ?: return@FilterChip
                                if (p.filter != f) {
                                    ScanActions.defaultFilter = f
                                    runWork { ScanActions.editPage(docId, p.id) { it.copy(filter = f) } }
                                }
                            },
                            label = { Text(f.label) },
                        )
                    }
                }
            }
        }
    }

    if (showExport) ExportSheet(doc) { showExport = false }

    if (showRename) {
        RenameDialog(doc.name, onDismiss = { showRename = false }) { name ->
            showRename = false
            scope.launch { ScanActions.rename(docId, name) }
        }
    }
    if (confirmDeletePage && current != null) {
        ConfirmDialog(
            title = "Delete page ${index + 1}?",
            message = "This page will be permanently removed.",
            confirmLabel = "Delete",
            onDismiss = { confirmDeletePage = false },
        ) {
            confirmDeletePage = false
            val id = current.id
            runWork { ScanActions.deletePages(docId, setOf(id)) }
        }
    }
    if (confirmDeleteDoc) {
        ConfirmDialog(
            title = "Delete document?",
            message = "\"${doc.name}\" and all its pages will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { confirmDeleteDoc = false },
        ) {
            confirmDeleteDoc = false
            App.scope.launch { DocumentRepository.delete(docId) }
            onBack()
        }
    }
    ocrText?.let { text ->
        AlertDialog(
            onDismissRequest = { ocrText = null },
            title = { Text("Extracted text") },
            text = {
                SelectionContainer {
                    Text(text, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    ocrText = null
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { ocrText = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = LocalContentColor.current.let { if (enabled) it else it.copy(alpha = 0.38f) }
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
