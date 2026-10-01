package com.smartscan.app.export

import com.smartscan.app.processing.OcrEngine
import java.io.OutputStream
import java.util.Locale
import kotlin.math.min

/**
 * Minimal PDF writer that embeds JPEG data directly (DCTDecode), so files stay small,
 * with an optional invisible OCR text layer that makes the PDF searchable/selectable.
 * Object 1 = Catalog, 2 = Pages, 3 = Font; pages are allocated from 4 upward.
 */
class PdfWriter(private val out: OutputStream) {
    private var pos = 0L
    private val offsets = HashMap<Int, Long>()
    private val pageObjects = mutableListOf<Int>()
    private var nextObj = 4

    init {
        write("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")
    }

    private fun write(s: String) {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        out.write(b); pos += b.size
    }

    private fun writeBytes(b: ByteArray) {
        out.write(b); pos += b.size
    }

    private fun beginObj(n: Int) {
        offsets[n] = pos
        write("$n 0 obj\n")
    }

    private fun endObj() = write("endobj\n")

    fun addPage(
        jpeg: ByteArray, imgW: Int, imgH: Int,
        pageW: Float, pageH: Float,
        textLines: List<OcrEngine.Line> = emptyList(),
    ) {
        val pageN = nextObj++
        val imgN = nextObj++
        val contentN = nextObj++

        val scale = min(pageW / imgW, pageH / imgH)
        val dw = imgW * scale
        val dh = imgH * scale
        val x = (pageW - dw) / 2
        val y = (pageH - dh) / 2

        val sb = StringBuilder()
        sb.append("q ${f(dw)} 0 0 ${f(dh)} ${f(x)} ${f(y)} cm /Im0 Do Q\n")
        if (textLines.isNotEmpty()) {
            sb.append("BT 3 Tr\n") // render mode 3 = invisible text
            for (line in textLines) {
                val text = line.text.trim()
                val bw = line.width * scale
                val bh = line.height * scale
                if (text.isEmpty() || bw < 1f || bh < 1f) continue
                val fontSize = bh * 0.85f
                val hScale = (100f * bw / (text.length * fontSize * 0.5f)).coerceIn(10f, 500f)
                val tx = x + line.left * scale
                val ty = y + dh - (line.top + line.height) * scale + bh * 0.2f
                sb.append("/F1 ${f(fontSize)} Tf ${f(hScale)} Tz 1 0 0 1 ${f(tx)} ${f(ty)} Tm (${escape(text)}) Tj\n")
            }
            sb.append("ET\n")
        }
        val content = sb.toString().toByteArray(Charsets.ISO_8859_1)

        beginObj(imgN)
        write("<< /Type /XObject /Subtype /Image /Width $imgW /Height $imgH /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
        writeBytes(jpeg)
        write("\nendstream\n")
        endObj()

        beginObj(contentN)
        write("<< /Length ${content.size} >>\nstream\n")
        writeBytes(content)
        write("\nendstream\n")
        endObj()

        beginObj(pageN)
        write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pageW)} ${f(pageH)}] /Resources << /XObject << /Im0 $imgN 0 R >> /Font << /F1 3 0 R >> >> /Contents $contentN 0 R >>\n")
        endObj()
        pageObjects += pageN
    }

    fun finish(title: String) {
        beginObj(3)
        write("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\n")
        endObj()
        beginObj(2)
        write("<< /Type /Pages /Kids [${pageObjects.joinToString(" ") { "$it 0 R" }}] /Count ${pageObjects.size} >>\n")
        endObj()
        beginObj(1)
        write("<< /Type /Catalog /Pages 2 0 R >>\n")
        endObj()
        val infoN = nextObj++
        beginObj(infoN)
        write("<< /Title (${escape(title)}) /Producer (SmartScan) >>\n")
        endObj()

        val xrefPos = pos
        val size = nextObj
        write("xref\n0 $size\n0000000000 65535 f \n")
        for (i in 1 until size) {
            write(String.format(Locale.US, "%010d 00000 n \n", offsets[i] ?: 0L))
        }
        write("trailer\n<< /Size $size /Root 1 0 R /Info $infoN 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        out.flush()
    }

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '(' || ch == ')' || ch == '\\' -> sb.append('\\').append(ch)
                ch.code in 32..126 || ch.code in 160..255 -> sb.append(ch)
                ch == '\t' -> sb.append(' ')
                else -> sb.append('?')
            }
        }
        return sb.toString()
    }
}
