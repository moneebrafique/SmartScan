package com.smartscan.app.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.FULL_CORNERS
import com.smartscan.app.data.Pt
import com.smartscan.app.processing.DocumentDetector
import com.smartscan.app.processing.ImageUtils
import com.smartscan.app.processing.ScanActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(docId: String, pageId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = remember(docId, pageId) { DocumentRepository.get(docId)?.pages?.firstOrNull { it.id == pageId } }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    val corners = remember { mutableStateListOf<Pt>().apply { addAll(page?.corners ?: FULL_CORNERS) } }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(page) {
        if (page == null) {
            onDone()
            return@LaunchedEffect
        }
        source = withContext(Dispatchers.Default) {
            ImageUtils.load(DocumentRepository.file(docId, page.originalFile), 1600)
        }
    }
    val image = remember(source) { source?.asImageBitmap() }
    val valid = DocumentDetector.isConvex(corners)

    fun applyCrop() {
        if (!valid || saving) return
        saving = true
        val ordered = DocumentDetector.order(corners.toList())
        scope.launch {
            ScanActions.editPage(docId, pageId) { it.copy(corners = ordered) }
            saving = false
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adjust borders") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                TextButton(onClick = {
                    val bmp = source ?: return@TextButton
                    scope.launch {
                        val q = withContext(Dispatchers.Default) { DocumentDetector.detect(bmp) }
                        if (q != null) {
                            corners.clear(); corners.addAll(q)
                        } else {
                            Toast.makeText(context, "No document edges found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Auto")
                }
                TextButton(onClick = { corners.clear(); corners.addAll(FULL_CORNERS) }) {
                    Icon(Icons.Default.Fullscreen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Full page")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { applyCrop() }, enabled = valid && !saving, modifier = Modifier.padding(end = 8.dp)) {
                    Text("Apply")
                }
            }
        },
    ) { pad ->
        Box(
            Modifier.padding(pad).fillMaxSize().background(Color(0xFF121212)),
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                CircularProgressIndicator()
            } else {
                CropEditor(image, corners, Modifier.fillMaxSize())
            }
            if (!valid) {
                Text(
                    "Borders are crossed — drag the corners apart",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .background(Color(0xCCB71C1C)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (saving) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun CropEditor(image: ImageBitmap, corners: SnapshotStateList<Pt>, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val margin = with(LocalDensity.current) { 28.dp.toPx() }
    var active by remember { mutableIntStateOf(-1) }
    val aspect = image.width.toFloat() / image.height

    Canvas(
        modifier.pointerInput(aspect, margin) {
            val grab = 44.dp.toPx()
            detectDragGestures(
                onDragStart = { start ->
                    val r = fitRect(size.width.toFloat(), size.height.toFloat(), aspect, margin)
                    val handles = handlePositions(corners, r)
                    val nearest = handles.indices.minBy { (handles[it] - start).getDistance() }
                    active = if ((handles[nearest] - start).getDistance() <= grab) nearest else -1
                },
                onDragEnd = { active = -1 },
                onDragCancel = { active = -1 },
                onDrag = { change, delta ->
                    val idx = active
                    if (idx >= 0) {
                        change.consume()
                        val r = fitRect(size.width.toFloat(), size.height.toFloat(), aspect, margin)
                        val dx = delta.x / r.width
                        val dy = delta.y / r.height
                        if (idx < 4) {
                            moveCorner(corners, idx, dx, dy)
                        } else {
                            // Edge handle: move both corners of that edge.
                            moveCorner(corners, idx - 4, dx, dy)
                            moveCorner(corners, (idx - 3) % 4, dx, dy)
                        }
                    }
                },
            )
        },
    ) {
        val r = fitRect(size.width, size.height, aspect, margin)
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
            dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
        )
        val valid = DocumentDetector.isConvex(corners)
        val color = if (valid) primary else Color(0xFFE53935)
        val quad = quadPath(corners, r)
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(r)
            addPath(quad)
        }
        drawPath(shade, Color.Black.copy(alpha = 0.45f))
        drawPath(quad, color, style = Stroke(width = 2.5.dp.toPx()))

        val handles = handlePositions(corners, r)
        handles.forEachIndexed { i, h ->
            val radius = if (i < 4) 12.dp.toPx() else 7.dp.toPx()
            drawCircle(Color.White, radius, h)
            drawCircle(color, radius, h, style = Stroke(if (i < 4) 3.dp.toPx() else 2.dp.toPx()))
        }
        val idx = active
        if (idx in handles.indices) drawLoupe(image, r, handles[idx], corners, color)
    }
}

private fun moveCorner(c: SnapshotStateList<Pt>, i: Int, dx: Float, dy: Float) {
    val p = c[i]
    c[i] = Pt((p.x + dx).coerceIn(0f, 1f), (p.y + dy).coerceIn(0f, 1f))
}

/** Magnifier shown while dragging, placed on the opposite side from the finger. */
private fun DrawScope.drawLoupe(image: ImageBitmap, r: Rect, focus: Offset, corners: List<Pt>, color: Color) {
    val radius = 60.dp.toPx()
    val zoom = 2.5f
    val pad = 12.dp.toPx()
    val center = if (focus.x < size.width / 2) Offset(size.width - radius - pad, radius + pad)
    else Offset(radius + pad, radius + pad)
    val scale = r.width / image.width * zoom
    val imgX = (focus.x - r.left) / r.width * image.width
    val imgY = (focus.y - r.top) / r.height * image.height

    clipPath(Path().apply { addOval(Rect(center, radius)) }) {
        drawRect(Color.Black, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((center.x - imgX * scale).roundToInt(), (center.y - imgY * scale).roundToInt()),
            dstSize = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt()),
        )
        val mapped = Path()
        corners.forEachIndexed { i, p ->
            val s = toScreen(p, r)
            val x = center.x + (s.x - focus.x) * zoom
            val y = center.y + (s.y - focus.y) * zoom
            if (i == 0) mapped.moveTo(x, y) else mapped.lineTo(x, y)
        }
        mapped.close()
        drawPath(mapped, color, style = Stroke(2.dp.toPx()))
    }
    drawCircle(Color.White, radius, center, style = Stroke(3.dp.toPx()))
    val l = 10.dp.toPx()
    drawLine(color, center - Offset(l, 0f), center + Offset(l, 0f), 2.dp.toPx())
    drawLine(color, center - Offset(0f, l), center + Offset(0f, l), 2.dp.toPx())
}
