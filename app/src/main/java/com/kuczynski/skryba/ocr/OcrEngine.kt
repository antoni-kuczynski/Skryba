package com.kuczynski.skryba.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import com.kuczynski.skryba.R
import com.kuczynski.skryba.data.Settings
import com.kuczynski.skryba.i18n.Lang
import com.kuczynski.skryba.i18n.str
import com.kuczynski.skryba.imaging.ImageIO
import java.io.File

data class OcrWord(val text: String, val box: Rect, val confidence: Float)

/** Rozpoznawanie tekstu offline (Tesseract). Modele językowe są w assets/tessdata. */
object OcrEngine {

    /** Kod Tesseract, nazwa polska, nazwa angielska. */
    val LANGUAGES: List<Triple<String, String, String>> = listOf(
        Triple("pol", "Polski", "Polish"), Triple("eng", "Angielski", "English"),
        Triple("deu", "Niemiecki", "German"), Triple("fra", "Francuski", "French"),
        Triple("spa", "Hiszpański", "Spanish"), Triple("ita", "Włoski", "Italian"),
        Triple("por", "Portugalski", "Portuguese"), Triple("nld", "Niderlandzki", "Dutch"),
        Triple("swe", "Szwedzki", "Swedish"), Triple("dan", "Duński", "Danish"),
        Triple("nor", "Norweski", "Norwegian"), Triple("fin", "Fiński", "Finnish"),
        Triple("ces", "Czeski", "Czech"), Triple("slk", "Słowacki", "Slovak"),
        Triple("slv", "Słoweński", "Slovenian"), Triple("hrv", "Chorwacki", "Croatian"),
        Triple("srp", "Serbski", "Serbian"), Triple("hun", "Węgierski", "Hungarian"),
        Triple("ron", "Rumuński", "Romanian"), Triple("bul", "Bułgarski", "Bulgarian"),
        Triple("ukr", "Ukraiński", "Ukrainian"), Triple("rus", "Rosyjski", "Russian"),
        Triple("bel", "Białoruski", "Belarusian"), Triple("lit", "Litewski", "Lithuanian"),
        Triple("lav", "Łotewski", "Latvian"), Triple("est", "Estoński", "Estonian"),
        Triple("ell", "Grecki", "Greek"), Triple("tur", "Turecki", "Turkish"),
        Triple("ara", "Arabski", "Arabic"), Triple("heb", "Hebrajski", "Hebrew"),
        Triple("hin", "Hindi", "Hindi"), Triple("vie", "Wietnamski", "Vietnamese"),
        Triple("tha", "Tajski", "Thai"), Triple("ind", "Indonezyjski", "Indonesian"),
        Triple("msa", "Malajski", "Malay"), Triple("fil", "Filipiński", "Filipino"),
        Triple("chi_sim", "Chiński uproszczony", "Chinese (simplified)"),
        Triple("chi_tra", "Chiński tradycyjny", "Chinese (traditional)"),
        Triple("jpn", "Japoński", "Japanese"), Triple("kor", "Koreański", "Korean"),
        Triple("cat", "Kataloński", "Catalan"),
    )

    fun label(code: String): String =
        LANGUAGES.firstOrNull { it.first == code }?.let { if (Lang.isPolish) it.second else it.third } ?: code

    private var api: TessBaseAPI? = null
    private var apiLangs: String? = null

    private fun dataRoot(context: Context) = File(context.filesDir, "tess").apply { File(this, "tessdata").mkdirs() }

    fun tessdataDir(context: Context) = File(dataRoot(context), "tessdata")

    /** Wszystkie dostępne języki: wbudowane i zaimportowane przez użytkownika. */
    fun availableLanguages(context: Context): List<String> {
        val bundled = context.assets.list("tessdata").orEmpty().map { it.removeSuffix(".traineddata") }
        val imported = tessdataDir(context).listFiles().orEmpty().map { it.name.removeSuffix(".traineddata") }
        return (LANGUAGES.map { it.first }.filter { it in bundled } + (bundled + imported).sorted()).distinct()
    }

    /** Kopiuje model z assets do katalogu, z którego czyta Tesseract (tylko przy pierwszym użyciu). */
    private fun ensureLang(context: Context, code: String): Boolean {
        val target = File(tessdataDir(context), "$code.traineddata")
        if (target.exists() && target.length() > 0) return true
        return runCatching {
            context.assets.open("tessdata/$code.traineddata").use { input ->
                val tmp = File(target.parentFile, "$code.tmp")
                tmp.outputStream().use { input.copyTo(it) }
                tmp.renameTo(target)
            }
        }.isSuccess
    }

    fun importLanguage(context: Context, file: File, code: String) {
        file.copyTo(File(tessdataDir(context), "$code.traineddata"), overwrite = true)
    }

    @Synchronized
    private fun engine(context: Context, langs: List<String>): TessBaseAPI {
        val ok = langs.filter { ensureLang(context, it) }.ifEmpty { listOf("eng").filter { ensureLang(context, it) } }
        val key = ok.joinToString("+")
        api?.let { if (apiLangs == key) return it }
        api?.recycle()
        val a = TessBaseAPI()
        check(a.init(dataRoot(context).path, key, TessBaseAPI.OEM_LSTM_ONLY)) { str(R.string.err_ocr_init, key) }
        a.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api = a
        apiLangs = key
        return a
    }

    private fun prepare(bmp: Bitmap): Bitmap = ImageIO.limit(bmp, 2800)

    @Synchronized
    fun recognize(context: Context, bmp: Bitmap, langs: List<String> = Settings.value.ocrLangs): String {
        val a = engine(context, langs)
        val img = prepare(bmp)
        a.setImage(img)
        val text = a.getUTF8Text().orEmpty()
        a.clear()
        if (img !== bmp) img.recycle()
        return cleanup(text)
    }

    /** Słowa z położeniem (w pikselach obrazu wejściowego) - do warstwy tekstu w PDF i do tabel. */
    @Synchronized
    fun recognizeWords(context: Context, bmp: Bitmap, langs: List<String> = Settings.value.ocrLangs): List<OcrWord> {
        val a = engine(context, langs)
        val img = prepare(bmp)
        val scale = bmp.width.toFloat() / img.width
        a.setImage(img)
        a.getUTF8Text()
        val words = ArrayList<OcrWord>()
        val it = a.resultIterator
        if (it != null) {
            it.begin()
            val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
            do {
                val t = it.getUTF8Text(level)?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    val r = it.getBoundingRect(level)
                    val box = Rect(
                        (r.left * scale).toInt(), (r.top * scale).toInt(),
                        (r.right * scale).toInt(), (r.bottom * scale).toInt(),
                    )
                    words += OcrWord(t, box, it.confidence(level))
                }
            } while (it.next(level))
            it.delete()
        }
        a.clear()
        if (img !== bmp) img.recycle()
        return words
    }

    /** Skleja słowa przeniesione z myślnikiem i usuwa nadmiarowe puste linie. */
    fun cleanup(text: String): String = text
        .replace("\r", "")
        .replace(Regex("(\\p{L})-\\n(\\p{Ll})"), "$1$2")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
