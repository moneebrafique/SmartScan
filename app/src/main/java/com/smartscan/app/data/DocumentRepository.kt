package com.smartscan.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Stores each document as a folder: docs/<id>/doc.json + page images. */
object DocumentRepository {
    private lateinit var root: File
    private val mutex = Mutex()
    private val _documents = MutableStateFlow<List<ScanDocument>>(emptyList())
    val documents: StateFlow<List<ScanDocument>> = _documents.asStateFlow()

    fun init(context: Context) {
        root = File(context.filesDir, "docs").apply { mkdirs() }
        _documents.value = root.listFiles()
            ?.mapNotNull { dir ->
                runCatching { fromJson(JSONObject(File(dir, "doc.json").readText())) }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun get(id: String): ScanDocument? = _documents.value.firstOrNull { it.id == id }

    fun docDir(id: String): File = File(root, id)

    fun file(docId: String, name: String): File = File(docDir(docId), name)

    suspend fun create(): ScanDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val name = "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(now))
            val doc = ScanDocument(UUID.randomUUID().toString(), name, now, now, emptyList())
            write(doc)
            doc
        }
    }

    /** Atomic read-modify-write of a document. */
    suspend fun update(docId: String, transform: (ScanDocument) -> ScanDocument): ScanDocument? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = get(docId) ?: return@withLock null
                val updated = transform(current).copy(updatedAt = System.currentTimeMillis())
                write(updated)
                updated
            }
        }

    suspend fun delete(docId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                docDir(docId).deleteRecursively()
                _documents.value = _documents.value.filterNot { it.id == docId }
            }
        }
    }

    private fun write(doc: ScanDocument) {
        val dir = docDir(doc.id).apply { mkdirs() }
        val tmp = File(dir, "doc.json.tmp")
        tmp.writeText(toJson(doc).toString())
        tmp.renameTo(File(dir, "doc.json"))
        _documents.value = (_documents.value.filterNot { it.id == doc.id } + doc)
            .sortedByDescending { it.updatedAt }
    }

    private fun toJson(d: ScanDocument): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("name", d.name)
        put("createdAt", d.createdAt)
        put("updatedAt", d.updatedAt)
        put("pages", JSONArray().apply {
            d.pages.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("original", p.originalFile)
                    put("processed", p.processedFile)
                    put("filter", p.filter.name)
                    put("rotation", p.rotation)
                    put("corners", JSONArray().apply {
                        p.corners.forEach { c -> put(c.x.toDouble()); put(c.y.toDouble()) }
                    })
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): ScanDocument {
        val arr = o.getJSONArray("pages")
        val pages = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val c = p.getJSONArray("corners")
            ScanPage(
                id = p.getString("id"),
                originalFile = p.getString("original"),
                processedFile = p.getString("processed"),
                corners = (0 until 4).map { k ->
                    Pt(c.getDouble(k * 2).toFloat(), c.getDouble(k * 2 + 1).toFloat())
                },
                filter = runCatching { FilterType.valueOf(p.getString("filter")) }
                    .getOrDefault(FilterType.MAGIC),
                rotation = p.optInt("rotation", 0),
            )
        }
        return ScanDocument(
            o.getString("id"), o.getString("name"),
            o.getLong("createdAt"), o.getLong("updatedAt"), pages,
        )
    }
}
