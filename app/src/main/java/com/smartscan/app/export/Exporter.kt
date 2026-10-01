package com.smartscan.app.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.ScanDocument
import com.smartscan.app.processing.ImageUtils
import com.smartscan.app.processing.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    PDF_OCR("Searchable PDF (OCR)", "pdf", "application/pdf"),
    JPG("JPG images", "jpg", "image/jpeg"),
    PNG("PNG images", "png", "image/png"),
    TXT("Text file (OCR)", "txt", "text/plain"),
}

enum class PdfPageSize(val label: String, val width: Float, val height: Float) {
    A4("A4", 595.28f, 841.89f),
    LETTER("Letter", 612f, 792f),
    FIT("Fit to image", 0f, 0f),
}

enum class ExportQuality(val label: String, val maxSide: Int, val jpegQuality: Int) {
    HIGH("High", 3000, 90),
    MEDIUM("Medium", 2000, 78),
    LOW("Small", 1300, 62),
}

data class ExportResult(val files: List<File>, val mime: String, val text: String? = null)

object Exporter {

    suspend fun export(
        context: Context,
        doc: ScanDocument,
        format: ExportFormat,
        pageSize: PdfPageSize,
        quality: ExportQuality,
        onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.Default) {
        require(doc.pages.isNotEmpty()) { "This document has no pages" }
        val dir = File(context.cacheDir, "exports").apply { deleteRecursively(); mkdirs() }
        val base = safeName(doc.name)
        val total = doc.pages.size

        when (format) {
            ExportFormat.PDF, ExportFormat.PDF_OCR -> {
                val file = File(dir, "$base.pdf")
                FileOutputStream(file).buffered().use { stream ->
                    val writer = PdfWriter(stream)
                    doc.pages.forEachIndexed { i, page ->
                        val bmp = ImageUtils.load(DocumentRepository.file(doc.id, page.processedFile), quality.maxSide)
                        val lines = if (format == ExportFormat.PDF_OCR) OcrEngine.recognize(bmp).lines else emptyList()
                        val (pw, ph) = pageDimensions(pageSize, bmp.width, bmp.height)
                        writer.addPage(ImageUtils.jpegBytes(bmp, quality.jpegQuality), bmp.width, bmp.height, pw, ph, lines)
                        bmp.recycle()
                        onProgress((i + 1f) / total)
                    }
                    writer.finish(doc.name)
                }
                ExportResult(listOf(file), format.mime)
            }

            ExportFormat.JPG, ExportFormat.PNG -> {
                val files = doc.pages.mapIndexed { i, page ->
                    val bmp = ImageUtils.load(DocumentRepository.file(doc.id, page.processedFile), quality.maxSide)
                    val file = File(dir, String.format(Locale.US, "%s_%02d.%s", base, i + 1, format.ext))
                    FileOutputStream(file).use {
                        val fmt = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        bmp.compress(fmt, quality.jpegQuality, it)
                    }
                    bmp.recycle()
                    onProgress((i + 1f) / total)
                    file
                }
                ExportResult(files, format.mime)
            }

            ExportFormat.TXT -> {
                val sb = StringBuilder()
                doc.pages.forEachIndexed { i, page ->
                    val bmp = ImageUtils.load(DocumentRepository.file(doc.id, page.processedFile), 2500)
                    val text = OcrEngine.recognize(bmp).text
                    bmp.recycle()
                    if (total > 1) sb.append("----- Page ${i + 1} -----\n")
                    sb.append(text.trim()).append("\n\n")
                    onProgress((i + 1f) / total)
                }
                val file = File(dir, "$base.txt")
                file.writeText(sb.toString())
                ExportResult(listOf(file), format.mime, sb.toString())
            }
        }
    }

    /** PDF page size in points; A4/Letter auto-rotate to landscape for wide pages. */
    private fun pageDimensions(size: PdfPageSize, w: Int, h: Int): Pair<Float, Float> = when (size) {
        PdfPageSize.FIT -> {
            val width = if (w > h) 841.89f else 595.28f
            width to width * h / w
        }
        else -> if (w > h) size.height to size.width else size.width to size.height
    }

    fun share(context: Context, result: ExportResult) {
        val authority = context.packageName + ".fileprovider"
        val uris = result.files.map { FileProvider.getUriForFile(context, authority, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
        }
        intent.type = result.mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    /** Saves into Download/SmartScan via MediaStore (no storage permission needed). */
    fun saveToDownloads(context: Context, result: ExportResult): Int {
        val resolver = context.contentResolver
        var saved = 0
        for (file in result.files) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, result.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SmartScan")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
            resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            saved++
        }
        return saved
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().take(60).ifBlank { "scan" }
}
