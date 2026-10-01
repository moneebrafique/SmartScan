package com.smartscan.app.data

/** Point in normalized image coordinates (0..1). */
data class Pt(val x: Float, val y: Float)

enum class FilterType(val label: String) {
    ORIGINAL("Original"),
    MAGIC("Magic Color"),
    LIGHTEN("Lighten"),
    GRAYSCALE("Grayscale"),
    BW("B&W"),
}

data class ScanPage(
    val id: String,
    val originalFile: String,   // untouched photo (EXIF-corrected)
    val processedFile: String,  // cropped + filtered + rotated result
    val corners: List<Pt>,      // TL, TR, BR, BL on the original
    val filter: FilterType,
    val rotation: Int,
)

data class ScanDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<ScanPage>,
)

val FULL_CORNERS = listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
