package com.kuczynski.skryba.data

import com.kuczynski.skryba.R
import com.kuczynski.skryba.i18n.str
import org.json.JSONArray
import org.json.JSONObject

/** Scan mode chosen in the camera. Determines the default filter and extra processing. */
enum class ScanMode(private val labelRes: Int, private val hintRes: Int) {
    DOCUMENT(R.string.mode_document, R.string.mode_document_hint),
    BOOK(R.string.mode_book, R.string.mode_book_hint),
    ID_CARD(R.string.mode_id, R.string.mode_id_hint),
    TEST_PAPER(R.string.mode_test, R.string.mode_test_hint),
    WHITEBOARD(R.string.mode_whiteboard, R.string.mode_whiteboard_hint),
    SLIDES(R.string.mode_slides, R.string.mode_slides_hint),
    TEXT(R.string.mode_text, R.string.mode_text_hint);

    val label: String get() = str(labelRes)
    val hint: String get() = str(hintRes)

    val defaultFilter: FilterType
        get() = when (this) {
            DOCUMENT, BOOK -> FilterType.MAGIC
            ID_CARD, SLIDES -> FilterType.AUTO
            TEST_PAPER -> FilterType.NO_INK
            WHITEBOARD -> FilterType.WHITEBOARD
            TEXT -> FilterType.GRAY
        }
}

enum class FilterType(private val labelRes: Int) {
    ORIGINAL(R.string.filter_original),
    AUTO(R.string.filter_auto),
    MAGIC(R.string.filter_magic),
    GRAY(R.string.filter_gray),
    BW(R.string.filter_bw),
    LIGHTEN(R.string.filter_lighten),
    WHITEBOARD(R.string.filter_whiteboard),
    NO_INK(R.string.filter_no_ink),
    ;

    val label: String get() = str(labelRes)
}

/** Element placed on a page after filters, e.g. a signature. Coordinates normalized to 0..1. */
data class Overlay(
    val image: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    fun toJson() = JSONObject().put("image", image).put("x", x.toDouble()).put("y", y.toDouble())
        .put("w", w.toDouble()).put("h", h.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = Overlay(
            o.getString("image"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
            o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
        )
    }
}

data class Page(
    val id: String,
    /** File name of the original photo in the document directory. */
    val original: String,
    /** File name of the processed page (this goes into the PDF). */
    val processed: String,
    /** 8 numbers (TL, TR, BR, BL) normalized to 0..1, or null = whole image. */
    val quad: FloatArray?,
    val filter: FilterType,
    val rotation: Int,
    val ocrText: String = "",
    val overlays: List<Overlay> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("original", original)
        put("processed", processed)
        quad?.let { q -> put("quad", JSONArray().apply { q.forEach { put(it.toDouble()) } }) }
        put("filter", filter.name)
        put("rotation", rotation)
        put("ocr", ocrText)
        put("overlays", JSONArray().apply { overlays.forEach { put(it.toJson()) } })
    }

    override fun equals(other: Any?): Boolean = other is Page && other.id == id &&
        other.processed == processed && other.ocrText == ocrText && other.overlays == overlays

    override fun hashCode(): Int = id.hashCode() * 31 + processed.hashCode()

    companion object {
        fun fromJson(o: JSONObject): Page {
            val q = o.optJSONArray("quad")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            val ov = o.optJSONArray("overlays")?.let { a ->
                (0 until a.length()).map { Overlay.fromJson(a.getJSONObject(it)) }
            } ?: emptyList()
            return Page(
                id = o.getString("id"),
                original = o.getString("original"),
                processed = o.getString("processed"),
                quad = q,
                filter = runCatching { FilterType.valueOf(o.getString("filter")) }.getOrDefault(FilterType.ORIGINAL),
                rotation = o.optInt("rotation", 0),
                ocrText = o.optString("ocr", ""),
                overlays = ov,
            )
        }
    }
}

data class ScanDocument(
    val id: String,
    val name: String,
    val created: Long,
    val modified: Long,
    val mode: ScanMode,
    val pages: List<Page>,
) {
    val fullText: String get() = pages.joinToString("\n\n") { it.ocrText }.trim()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("created", created)
        put("modified", modified)
        put("mode", mode.name)
        put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): ScanDocument {
            val arr = o.getJSONArray("pages")
            return ScanDocument(
                id = o.getString("id"),
                name = o.getString("name"),
                created = o.getLong("created"),
                modified = o.getLong("modified"),
                mode = runCatching { ScanMode.valueOf(o.getString("mode")) }.getOrDefault(ScanMode.DOCUMENT),
                pages = (0 until arr.length()).map { Page.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}
