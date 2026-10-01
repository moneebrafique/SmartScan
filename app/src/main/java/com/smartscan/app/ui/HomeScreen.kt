package com.smartscan.app.ui

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.ScanDocument
import com.smartscan.app.export.ExportFormat
import com.smartscan.app.export.ExportQuality
import com.smartscan.app.export.Exporter
import com.smartscan.app.export.PdfPageSize
import com.smartscan.app.processing.ScanActions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by DocumentRepository.documents.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var busyText by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ScanDocument?>(null) }
    var deleteTarget by remember { mutableStateOf<ScanDocument?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val doc = DocumentRepository.create()
            uris.forEachIndexed { i, uri ->
                busyText = "Importing ${i + 1} of ${uris.size}…"
                runCatching { ScanActions.addPage(context, doc.id, uri) }
                    .onFailure { Log.w("SmartScan", "Import failed", it) }
            }
            busyText = null
            onOpen(doc.id)
        }
    }

    fun quickSharePdf(doc: ScanDocument) {
        if (doc.pages.isEmpty()) return
        scope.launch {
            busyText = "Preparing PDF…"
            try {
                val result = Exporter.export(context, doc, ExportFormat.PDF, PdfPageSize.A4, ExportQuality.MEDIUM) {}
                Exporter.share(context, result)
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busyText = null
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SmartScan", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Icon(Icons.Default.PhotoLibrary, contentDescription = "Import from gallery") }
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                    text = { Text("Scan") },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (docs.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.DocumentScanner, contentDescription = null,
                        modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No documents yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Tap Scan to capture a document, or import photos from your gallery.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search documents") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val filtered = docs.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { doc ->
                        DocumentRow(
                            doc = doc,
                            onClick = { onOpen(doc.id) },
                            onSharePdf = { quickSharePdf(doc) },
                            onRename = { renameTarget = doc },
                            onDelete = { deleteTarget = doc },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { doc ->
        RenameDialog(doc.name, onDismiss = { renameTarget = null }) { name ->
            renameTarget = null
            scope.launch { ScanActions.rename(doc.id, name) }
        }
    }
    deleteTarget?.let { doc ->
        ConfirmDialog(
            title = "Delete document?",
            message = "\"${doc.name}\" and all its pages will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { deleteTarget = null },
        ) {
            deleteTarget = null
            scope.launch { DocumentRepository.delete(doc.id) }
        }
    }
    busyText?.let { BusyDialog(it) }
}

@Composable
private fun DocumentRow(
    doc: ScanDocument,
    onClick: () -> Unit,
    onSharePdf: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val date = remember(doc.updatedAt) {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(doc.updatedAt))
    }
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val first = doc.pages.firstOrNull()
            Box(
                Modifier.size(width = 56.dp, height = 72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (first != null) {
                    AsyncImage(
                        model = DocumentRepository.file(doc.id, first.processedFile),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Default.Description, contentDescription = null)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"} • $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Share as PDF") },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                        enabled = doc.pages.isNotEmpty(),
                        onClick = { menu = false; onSharePdf() },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
