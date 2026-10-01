package com.smartscan.app.processing

import android.graphics.Bitmap
import com.smartscan.app.data.FilterType
import com.smartscan.app.data.Pt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Perspective correction + scan filters. */
object ImageProcessor {

    /**
     * Tone settings per filter.
     * black/white = levels points (paper above `white` becomes pure white, ink below `black` pure black),
     * gamma > 1 darkens mid-tones (ink), chroma scales colour intensity, sharpen = unsharp-mask amount.
     */
    private data class Tone(
        val black: Int, val white: Int, val gamma: Double,
        val chroma: Double, val sharpen: Double,
    )

    private val LIGHTEN = Tone(black = 0, white = 238, gamma = 1.0, chroma = 1.0, sharpen = 0.3)
    private val MAGIC = Tone(black = 28, white = 222, gamma = 1.2, chroma = 1.4, sharpen = 0.6)
    private val GRAY = Tone(black = 18, white = 232, gamma = 1.15, chroma = 0.0, sharpen = 0.5)

    fun process(src: Bitmap, corners: List<Pt>, filter: FilterType, rotation: Int): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val warped = warp(rgba, corners)
        rgba.release()
        val filtered = applyFilter(warped, filter)
        if (filtered !== warped) warped.release()
        val rotated = rotate(filtered, rotation)
        if (rotated !== filtered) filtered.release()
        val out = Bitmap.createBitmap(rotated.cols(), rotated.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rotated, out)
        rotated.release()
        return out
    }

    private fun warp(rgba: Mat, corners: List<Pt>): Mat {
        val w = rgba.cols().toDouble()
        val h = rgba.rows().toDouble()
        val (tl, tr, br, bl) = DocumentDetector.order(corners).map { Point(it.x * w, it.y * h) }
        val outW = max(dist(tl, tr), dist(bl, br)).roundToInt().coerceAtLeast(16)
        val outH = max(dist(tl, bl), dist(tr, br)).roundToInt().coerceAtLeast(16)
        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(
            rgba, out, m, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
        )
        srcPts.release(); dstPts.release(); m.release()
        return out
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun applyFilter(rgba: Mat, filter: FilterType): Mat = when (filter) {
        FilterType.ORIGINAL -> rgba
        FilterType.MAGIC -> enhanceColor(rgba, MAGIC)
        FilterType.LIGHTEN -> enhanceColor(rgba, LIGHTEN)
        FilterType.GRAYSCALE -> enhanceGray(rgba, GRAY)
        FilterType.BW -> blackWhite(rgba)
    }

    // ---------------------------------------------------------------- colour filters

    /**
     * Works in Lab colour space: only the lightness channel is corrected (shadow removal + levels +
     * sharpening), so ink colours stay true and there are no colour casts. Chroma is then boosted.
     */
    private fun enhanceColor(rgba: Mat, tone: Tone): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)

        val l = correctLightness(ch[0], tone)
        ch[0].release()
        ch[0] = l

        if (tone.chroma != 1.0) {
            // a/b are stored with an offset of 128: scale the distance from neutral grey.
            val beta = 128.0 * (1.0 - tone.chroma)
            ch[1].convertTo(ch[1], -1, tone.chroma, beta)
            ch[2].convertTo(ch[2], -1, tone.chroma, beta)
        }

        // Make the paper itself neutral white (removes yellow/blue paper or light tint).
        val paperMask = Mat()
        Imgproc.threshold(ch[0], paperMask, 232.0, 255.0, Imgproc.THRESH_BINARY)
        ch[1].setTo(Scalar(128.0), paperMask)
        ch[2].setTo(Scalar(128.0), paperMask)
        paperMask.release()

        Core.merge(ch, lab)
        ch.forEach { it.release() }
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return rgb
    }

    private fun enhanceGray(rgba: Mat, tone: Tone): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val out = correctLightness(gray, tone)
        gray.release()
        return out
    }

    /** Shadow removal -> levels/gamma -> sharpen, on an 8-bit single channel. */
    private fun correctLightness(channel: Mat, tone: Tone): Mat {
        val bg = estimateBackground(channel)
        val flat = Mat()
        Core.divide(channel, bg, flat, 255.0)
        bg.release()

        val lut = levelsLut(tone.black, tone.white, tone.gamma)
        val leveled = Mat()
        Core.LUT(flat, lut, leveled)
        flat.release(); lut.release()

        sharpen(leveled, tone.sharpen)
        return leveled
    }

    /**
     * Estimates the paper's brightness everywhere (removes text by a max filter, smooths it),
     * so dividing by it evens out shadows and uneven light. Gain is capped so that photos or
     * big dark areas on the page are not blown out to white.
     */
    private fun estimateBackground(channel: Mat): Mat {
        val f = (1000.0 / max(channel.cols(), channel.rows())).coerceAtMost(1.0)
        val small = Mat()
        Imgproc.resize(channel, small, Size(), f, f, Imgproc.INTER_AREA)

        var k = max(small.cols(), small.rows()) / 60
        if (k % 2 == 0) k += 1
        k = k.coerceAtLeast(9)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        Imgproc.dilate(small, small, kernel)
        kernel.release()
        Imgproc.medianBlur(small, small, (k * 2 + 1).coerceAtMost(255))
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), k.toDouble())

        val bg = Mat()
        Imgproc.resize(small, bg, channel.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()

        val paper = Core.minMaxLoc(bg).maxVal
        Core.max(bg, Scalar(max(paper * 0.45, 1.0)), bg) // at most ~2.2x brightening
        return bg
    }

    private fun levelsLut(black: Int, white: Int, gamma: Double): Mat {
        val bytes = ByteArray(256)
        val range = (white - black).coerceAtLeast(1).toDouble()
        for (i in 0 until 256) {
            val v = ((i - black) / range).coerceIn(0.0, 1.0).pow(gamma)
            bytes[i] = (v * 255.0).roundToInt().coerceIn(0, 255).toByte()
        }
        val lut = Mat(1, 256, CvType.CV_8U)
        lut.put(0, 0, bytes)
        return lut
    }

    private fun sharpen(m: Mat, amount: Double) {
        if (amount <= 0.0) return
        val blur = Mat()
        Imgproc.GaussianBlur(m, blur, Size(0.0, 0.0), 1.5)
        Core.addWeighted(m, 1.0 + amount, blur, -amount, 0.0, m)
        blur.release()
    }

    // ---------------------------------------------------------------- black & white (unchanged)

    private fun flattenBackgroundBw(channel: Mat): Mat {
        val f = (800.0 / max(channel.cols(), channel.rows())).coerceAtMost(1.0)
        val small = Mat()
        Imgproc.resize(channel, small, Size(), f, f, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.dilate(small, small, k)
        Imgproc.medianBlur(small, small, 21)
        val bg = Mat()
        Imgproc.resize(small, bg, channel.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val out = Mat()
        Core.divide(channel, bg, out, 255.0)
        small.release(); bg.release(); k.release()
        return out
    }

    private fun blackWhite(rgba: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        val flat = flattenBackgroundBw(g)
        g.release()
        var block = max(flat.cols(), flat.rows()) / 60
        if (block % 2 == 0) block += 1
        block = block.coerceAtLeast(15)
        val out = Mat()
        Imgproc.adaptiveThreshold(
            flat, out, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, block, 12.0,
        )
        flat.release()
        return out
    }

    private fun rotate(m: Mat, degrees: Int): Mat = when (((degrees % 360) + 360) % 360) {
        90 -> Mat().also { Core.rotate(m, it, Core.ROTATE_90_CLOCKWISE) }
        180 -> Mat().also { Core.rotate(m, it, Core.ROTATE_180) }
        270 -> Mat().also { Core.rotate(m, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
        else -> m
    }
}
