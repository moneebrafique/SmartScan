package com.smartscan.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartscan.app.data.ScanDocument
import com.smartscan.app.export.ExportFormat
import com.smartscan.app.export.ExportQuality
import com.smartscan.app.export.Exporter
import com.smartscan.app.export.PdfPageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(doc: ScanDocument, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
    var pageSize by rememberSaveable { mutableStateOf(PdfPageSize.A4) }
    var quality by rememberSaveable { mutableStateOf(ExportQuality.MEDIUM) }
    var progress by remember { mutableStateOf<Float?>(null) }

    fun run(share: Boolean) {
        if (progress != null) return
        progress = 0f
        scope.launch {
            try {
                val result = Exporter.export(context, doc, format, pageSize, quality) { p -> progress = p }
                if (share) {
                    Exporter.share(context, result)
                } else {
                    val n = withContext(Dispatchers.IO) { Exporter.saveToDownloads(context, result) }
                    Toast.makeText(context, "Saved $n file(s) to Downloads/SmartScan", Toast.LENGTH_LONG).show()
                }
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress = null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (progress == null) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Text("Export", style = MaterialTheme.typography.titleLarge)
            Text(
                "${doc.name} • ${doc.pages.size} page(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("Format")
            ExportFormat.entries.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = f == format, onClick = { format = f })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = f == format, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(f.label)
                }
            }

            if (format == ExportFormat.PDF || format == ExportFormat.PDF_OCR) {
                SectionLabel("Page size")
                ChipRow(PdfPageSize.entries, pageSize, { it.label }) { pageSize = it }
            }
            if (format != ExportFormat.TXT) {
                SectionLabel("Quality")
                ChipRow(ExportQuality.entries, quality, { it.label }) { quality = it }
            }

            Spacer(Modifier.height(20.dp))
            progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { run(share = false) }, enabled = progress == null, modifier = Modifier.weight(1f)) {
                    Text("Save to Downloads")
                }
                Button(onClick = { run(share = true) }, enabled = progress == null, modifier = Modifier.weight(1f)) {
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}
