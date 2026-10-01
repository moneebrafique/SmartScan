package com.smartscan.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.smartscan.app.App
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.Pt
import com.smartscan.app.data.ScanPage
import com.smartscan.app.processing.DocumentDetector
import com.smartscan.app.processing.ImageUtils
import com.smartscan.app.processing.ScanActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors

private const val STEADY_FRAMES = 8

@Composable
fun CameraScreen(
    initialDocId: String?,
    onFinished: (docId: String, cropPageId: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    var docId by remember { mutableStateOf(initialDocId) }
    val startPageIds = remember {
        DocumentRepository.get(initialDocId ?: "")?.pages?.map { it.id }?.toSet() ?: emptySet()
    }
    val docs by DocumentRepository.documents.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.id == docId }
    val newPages = doc?.pages?.filterNot { it.id in startPageIds } ?: emptyList()

    var pending by remember { mutableIntStateOf(0) }
    var batchMode by rememberSaveable { mutableStateOf(true) }
    var autoCapture by rememberSaveable { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var shutterBusy by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // Written from the analysis thread.
    val liveQuad = remember { mutableStateOf<List<Pt>?>(null) }
    val frameAspect = remember { mutableFloatStateOf(0.75f) }
    val steadyProgress = remember { mutableFloatStateOf(0f) }
    val waitingForNextPage = remember { mutableStateOf(false) }
    val autoTrigger = remember { mutableIntStateOf(0) }
    val autoState = rememberUpdatedState(autoCapture)

    val flashAlpha = remember { Animatable(0f) }
    val docLock = remember { Mutex() }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    val analyzer = remember {
        var previous: List<Pt>? = null
        var capturedQuad: List<Pt>? = null
        var armed = true
        var steady = 0
        var lastRun = 0L
        var lastAuto = 0L
        ImageAnalysis.Analyzer { proxy ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastRun >= 90) {
                    lastRun = now
                    val frame = ImageUtils.rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
                    frameAspect.floatValue = frame.width.toFloat() / frame.height
                    val quad = DocumentDetector.detect(frame)
                    frame.recycle()

                    val prev = previous
                    steady = if (quad != null && prev != null && DocumentDetector.maxShift(quad, prev) < 0.02f) steady + 1 else 0
                    previous = quad
                    liveQuad.value = quad

                    // After an auto-capture, wait until the page is moved/replaced before capturing again.
                    if (!armed) {
                        val cq = capturedQuad
                        if (quad == null || cq == null || DocumentDetector.maxShift(quad, cq) > 0.1f) armed = true
                    }
                    waitingForNextPage.value = !armed

                    val auto = autoState.value
                    steadyProgress.floatValue =
                        if (auto && armed && quad != null) (steady / STEADY_FRAMES.toFloat()).coerceAtMost(1f) else 0f
                    if (auto && armed && quad != null && steady >= STEADY_FRAMES && now - lastAuto > 1500) {
                        lastAuto = now
                        steady = 0
                        armed = false
                        capturedQuad = quad
                        autoTrigger.intValue = autoTrigger.intValue + 1
                    }
                }
            } catch (t: Throwable) {
                Log.w("SmartScan", "Frame analysis failed", t)
            } finally {
                proxy.close()
            }
        }
    }

    DisposableEffect(hasPermission, lifecycleOwner) {
        if (!hasPermission) return@DisposableEffect onDispose { }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val ratio43 = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(ratio43).build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor, analyzer)
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis,
                )
            } catch (e: Exception) {
                Log.e("SmartScan", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { if (future.isDone) future.get().unbindAll() }
            camera = null
        }
    }

    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    suspend fun ensureDoc(): String = docLock.withLock {
        docId ?: DocumentRepository.create().id.also { docId = it }
    }

    fun processImage(uri: Uri, temp: File?, allowSingleNavigate: Boolean) {
        pending++
        scope.launch {
            try {
                val id = ensureDoc()
                val page = ScanActions.addPage(context.applicationContext, id, uri)
                if (allowSingleNavigate && !batchMode) onFinished(id, page.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SmartScan", "Processing failed", e)
                Toast.makeText(context, "Could not process image", Toast.LENGTH_SHORT).show()
            } finally {
                temp?.delete()
                pending--
            }
        }
    }

    fun capture() {
        if (shutterBusy || camera == null) return
        shutterBusy = true
        scope.launch {
            flashAlpha.snapTo(0.7f)
            flashAlpha.animateTo(0f, tween(300))
        }
        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    shutterBusy = false
                    processImage(Uri.fromFile(file), file, allowSingleNavigate = true)
                }

                override fun onError(exception: ImageCaptureException) {
                    shutterBusy = false
                    file.delete()
                    Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    LaunchedEffect(autoTrigger.intValue) {
        if (autoTrigger.intValue > 0 && autoCapture && (batchMode || pending == 0)) capture()
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        uris.forEach { processImage(it, null, allowSingleNavigate = false) }
    }

    fun requestClose() {
        if (newPages.isNotEmpty() || pending > 0) {
            showDiscard = true
        } else {
            val id = docId
            if (initialDocId == null && id != null) App.scope.launch { DocumentRepository.delete(id) }
            onCancel()
        }
    }
    BackHandler { requestClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasPermission) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera permission is needed to scan documents.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text("Open settings") }
                TextButton(onClick = onCancel) { Text("Close") }
            }
        } else {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // Top controls
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { requestClose() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    if (camera?.cameraInfo?.hasFlashUnit() == true) {
                        IconButton(onClick = { torch = !torch }) {
                            Icon(
                                if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Torch", tint = Color.White,
                            )
                        }
                    }
                    ModeToggle(if (autoCapture) "AUTO" else "MANUAL", autoCapture, primary) { autoCapture = !autoCapture }
                    ModeToggle(if (batchMode) "BATCH" else "SINGLE", batchMode, primary) { batchMode = !batchMode }
                }

                // Viewfinder
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val cam = camera ?: return@detectTapGestures
                                val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                                cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                            }
                        },
                    ) {
                        val quad = liveQuad.value ?: return@Canvas
                        val r = fitRect(size.width, size.height, frameAspect.floatValue)
                        val path = quadPath(quad, r)
                        drawPath(path, primary.copy(alpha = 0.22f))
                        drawPath(path, primary, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
                    }
                    val status = when {
                        waitingForNextPage.value && autoCapture -> "Captured — place the next page"
                        liveQuad.value == null -> "Looking for document…"
                        autoCapture && steadyProgress.floatValue > 0f -> "Hold steady…"
                        else -> "Document detected"
                    }
                    Text(
                        status,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha.value)))
                }

                // Bottom controls
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(Icons.Default.PhotoLibrary, "Import from gallery", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    ShutterButton(
                        progress = steadyProgress.floatValue,
                        enabled = !shutterBusy && camera != null,
                        color = primary,
                        onClick = { capture() },
                    )
                    DoneButton(
                        docId = docId,
                        lastPage = newPages.lastOrNull(),
                        count = newPages.size,
                        pending = pending,
                        onClick = {
                            val id = docId
                            if (id != null && pending == 0 && newPages.isNotEmpty()) onFinished(id, null)
                        },
                    )
                }
            }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard new scans?") },
            text = { Text("${newPages.size} new page(s) will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    val id = docId
                    val ids = newPages.map { it.id }.toSet()
                    App.scope.launch {
                        if (id != null) {
                            if (initialDocId == null) DocumentRepository.delete(id) else ScanActions.deletePages(id, ids)
                        }
                    }
                    onCancel()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(
                    enabled = pending == 0,
                    onClick = {
                        showDiscard = false
                        val id = docId
                        if (id != null) onFinished(id, null)
                    },
                ) { Text("Save & review") }
            },
        )
    }
}

@Composable
private fun ModeToggle(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = if (active) activeColor else Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ShutterButton(progress: Float, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(78.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            drawCircle(Color.White, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (progress > 0f) {
                drawArc(
                    color = color, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            drawCircle(if (enabled) Color.White else Color.Gray, radius = size.minDimension / 2 - 11.dp.toPx())
        }
    }
}

@Composable
private fun DoneButton(docId: String?, lastPage: ScanPage?, count: Int, pending: Int, onClick: () -> Unit) {
    Box(Modifier.size(56.dp)) {
        if (count > 0 || pending > 0) {
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.DarkGray)
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                    .clickable(enabled = pending == 0, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (lastPage != null && docId != null) {
                    AsyncImage(
                        model = DocumentRepository.file(docId, lastPage.processedFile),
                        contentDescription = "Review scans",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (pending > 0) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            }
            if (count > 0) {
                Badge(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp)) { Text("$count") }
            }
        }
    }
}
