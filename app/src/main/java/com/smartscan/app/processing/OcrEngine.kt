package com.smartscan.app.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** On-device OCR (Latin script) via ML Kit. */
object OcrEngine {
    data class Line(val text: String, val left: Float, val top: Float, val width: Float, val height: Float)
    data class Result(val text: String, val lines: List<Line>)

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): Result {
        val r = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = r.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                line.boundingBox?.let { b ->
                    Line(line.text, b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat())
                }
            }
        }
        return Result(r.text, lines)
    }
}
