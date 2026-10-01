package com.smartscan.app.processing

import android.graphics.Bitmap
import com.smartscan.app.data.FilterType
import com.smartscan.app.data.Pt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Perspective correction + scan filters. */
object ImageProcessor {

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
        FilterType.MAGIC -> magicColor(rgba)
        FilterType.LIGHTEN -> Mat().also { rgba.convertTo(it, -1, 1.15, 28.0) }
        FilterType.GRAYSCALE -> grayscale(rgba)
        FilterType.BW -> blackWhite(rgba)
    }

    /**
     * Removes shadows / uneven lighting: estimates the paper background
     * (text removed by dilation + median blur) and divides it out.
     */
    private fun flattenBackground(channel: Mat): Mat {
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

    private fun magicColor(rgba: Mat): Mat {
        val channels = ArrayList<Mat>()
        Core.split(rgba, channels)
        val flat = ArrayList<Mat>()
        for (i in 0 until 3) flat.add(flattenBackground(channels[i]))
        channels.forEach { it.release() }
        val rgb = Mat()
        Core.merge(flat, rgb)
        flat.forEach { it.release() }
        rgb.convertTo(rgb, -1, 1.12, -18.0) // extra contrast

        // Boost saturation so stamps, highlights and diagrams pop.
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val hsvCh = ArrayList<Mat>()
        Core.split(hsv, hsvCh)
        hsvCh[1].convertTo(hsvCh[1], -1, 1.25, 0.0)
        Core.merge(hsvCh, hsv)
        hsvCh.forEach { it.release() }
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        return rgb
    }

    private fun grayscale(rgba: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        val out = Mat()
        Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(g, out)
        g.release()
        return out
    }

    private fun blackWhite(rgba: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        val flat = flattenBackground(g)
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
