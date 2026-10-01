package com.smartscan.app.processing

import android.graphics.Bitmap
import com.smartscan.app.data.Pt
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

/** Finds the four corners of a document in a photo. */
object DocumentDetector {
    private const val WORK_SIZE = 480f

    /** Returns normalized corners ordered TL, TR, BR, BL, or null if none found. */
    fun detect(bitmap: Bitmap): List<Pt>? {
        val scale = WORK_SIZE / max(bitmap.width, bitmap.height)
        val work = if (scale < 1f) Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        ) else bitmap
        val rgba = Mat()
        Utils.bitmapToMat(work, rgba)
        if (work !== bitmap) work.recycle()
        try {
            val quad = findQuad(rgba) ?: return null
            val w = rgba.cols().toDouble()
            val h = rgba.rows().toDouble()
            return order(quad.map {
                Pt((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f))
            })
        } finally {
            rgba.release()
        }
    }

    private fun findQuad(rgba: Mat): List<Point>? {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val area = gray.rows().toDouble() * gray.cols()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val bin = Mat()
        try {
            // Three strategies, from strongest to most permissive.
            for (attempt in 0 until 3) {
                when (attempt) {
                    0 -> { Imgproc.Canny(gray, bin, 60.0, 180.0); Imgproc.dilate(bin, bin, kernel) }
                    1 -> { Imgproc.Canny(gray, bin, 20.0, 60.0); Imgproc.dilate(bin, bin, kernel) }
                    else -> {
                        Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
                    }
                }
                bestQuad(bin, area)?.let { return it }
            }
            return null
        } finally {
            gray.release(); kernel.release(); bin.release()
        }
    }

    private fun bestQuad(bin: Mat, imageArea: Double): List<Point>? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        var best: List<Point>? = null
        var bestArea = imageArea * 0.12
        for (c in contours) {
            if (Imgproc.contourArea(c) >= bestArea) {
                val c2f = MatOfPoint2f(*c.toArray())
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true)
                if (approx.total() == 4L) {
                    val pts = approx.toArray()
                    val asInt = MatOfPoint(*pts)
                    val quadArea = Imgproc.contourArea(approx)
                    if (Imgproc.isContourConvex(asInt) && quadArea > bestArea && quadArea < imageArea * 0.99) {
                        bestArea = quadArea
                        best = pts.toList()
                    }
                    asInt.release()
                }
                c2f.release()
                approx.release()
            }
            c.release()
        }
        return best
    }

    /** Orders 4 points clockwise starting at top-left. */
    fun order(pts: List<Pt>): List<Pt> {
        if (pts.size != 4) return pts
        val cx = pts.sumOf { it.x.toDouble() } / 4
        val cy = pts.sumOf { it.y.toDouble() } / 4
        val sorted = pts.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }
    }

    fun isConvex(pts: List<Pt>): Boolean {
        if (pts.size != 4) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]; val b = pts[(i + 1) % 4]; val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    fun maxShift(a: List<Pt>, b: List<Pt>): Float =
        a.indices.maxOf { max(abs(a[it].x - b[it].x), abs(a[it].y - b[it].y)) }
}
