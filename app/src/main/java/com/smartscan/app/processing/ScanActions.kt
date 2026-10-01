package com.smartscan.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.FULL_CORNERS
import com.smartscan.app.data.FilterType
import com.smartscan.app.data.ScanPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.UUID

/** High-level page operations. Heavy work is serialized to keep memory under control. */
object ScanActions {
    var defaultFilter: FilterType = FilterType.MAGIC
    private val workLock = Mutex()

    suspend fun addPage(context: Context, docId: String, uri: Uri): ScanPage =
        workLock.withLock {
            withContext(Dispatchers.Default) {
                val bitmap = ImageUtils.load(context, uri)
                try {
                    val id = UUID.randomUUID().toString().take(8)
                    val original = "o_$id.jpg"
                    ImageUtils.saveJpeg(bitmap, DocumentRepository.file(docId, original), 95)
                    val corners = DocumentDetector.detect(bitmap) ?: FULL_CORNERS
                    val draft = ScanPage(id, original, "", corners, defaultFilter, 0)
                    val page = draft.copy(processedFile = render(docId, draft, bitmap))
                    DocumentRepository.update(docId) { it.copy(pages = it.pages + page) }
                    page
                } finally {
                    bitmap.recycle()
                }
            }
        }

    suspend fun editPage(docId: String, pageId: String, change: (ScanPage) -> ScanPage) {
        workLock.withLock {
            withContext(Dispatchers.Default) {
                val old = DocumentRepository.get(docId)?.pages?.firstOrNull { it.id == pageId }
                    ?: return@withContext
                val edited = change(old)
                val name = render(docId, edited, null)
                val final = edited.copy(processedFile = name)
                DocumentRepository.update(docId) { d ->
                    d.copy(pages = d.pages.map { if (it.id == pageId) final else it })
                }
                if (old.processedFile.isNotEmpty() && old.processedFile != name) {
                    DocumentRepository.file(docId, old.processedFile).delete()
                }
            }
        }
    }

    suspend fun applyFilterToAll(docId: String, filter: FilterType) {
        val pages = DocumentRepository.get(docId)?.pages ?: return
        for (p in pages) {
            if (p.filter != filter) editPage(docId, p.id) { it.copy(filter = filter) }
        }
    }

    suspend fun deletePages(docId: String, ids: Set<String>) {
        val removed = DocumentRepository.get(docId)?.pages?.filter { it.id in ids } ?: return
        DocumentRepository.update(docId) { d -> d.copy(pages = d.pages.filterNot { it.id in ids }) }
        withContext(Dispatchers.IO) {
            removed.forEach {
                DocumentRepository.file(docId, it.originalFile).delete()
                DocumentRepository.file(docId, it.processedFile).delete()
            }
        }
    }

    suspend fun movePage(docId: String, pageId: String, delta: Int) {
        DocumentRepository.update(docId) { d ->
            val list = d.pages.toMutableList()
            val i = list.indexOfFirst { it.id == pageId }
            val j = i + delta
            if (i < 0 || j !in list.indices) d else {
                Collections.swap(list, i, j)
                d.copy(pages = list)
            }
        }
    }

    suspend fun rename(docId: String, name: String) {
        DocumentRepository.update(docId) { it.copy(name = name) }
    }

    /** Renders a page and returns the new processed file name (unique, so image caches never go stale). */
    private fun render(docId: String, page: ScanPage, source: Bitmap?): String {
        val src = source ?: ImageUtils.load(DocumentRepository.file(docId, page.originalFile))
        val out = ImageProcessor.process(src, page.corners, page.filter, page.rotation)
        if (source == null) src.recycle()
        val name = "p_${page.id}_${System.currentTimeMillis()}.jpg"
        ImageUtils.saveJpeg(out, DocumentRepository.file(docId, name), 90)
        out.recycle()
        return name
    }
}
