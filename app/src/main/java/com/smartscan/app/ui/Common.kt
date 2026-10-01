package com.smartscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.smartscan.app.data.Pt

/** Rect where an image of the given aspect sits when fitted (centered) inside width x height. */
fun fitRect(width: Float, height: Float, aspect: Float, margin: Float = 0f): Rect {
    val w = (width - 2 * margin).coerceAtLeast(1f)
    val h = (height - 2 * margin).coerceAtLeast(1f)
    return if (w / h > aspect) {
        val rw = h * aspect
        val left = margin + (w - rw) / 2
        Rect(left, margin, left + rw, margin + h)
    } else {
        val rh = w / aspect
        val top = margin + (h - rh) / 2
        Rect(margin, top, margin + w, top + rh)
    }
}

fun toScreen(p: Pt, r: Rect) = Offset(r.left + p.x * r.width, r.top + p.y * r.height)

fun quadPath(q: List<Pt>, r: Rect): Path = Path().apply {
    q.forEachIndexed { i, p ->
        val o = toScreen(p, r)
        if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
    }
    close()
}

/** 4 corner handles followed by 4 edge-midpoint handles. */
fun handlePositions(c: List<Pt>, r: Rect): List<Offset> {
    val p = c.map { toScreen(it, r) }
    return p + List(4) { i -> (p[i] + p[(i + 1) % 4]) / 2f }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BusyDialog(text: String) {
    Dialog(onDismissRequest = {}) {
        Card {
            Row(
                Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(text)
            }
        }
    }
}
