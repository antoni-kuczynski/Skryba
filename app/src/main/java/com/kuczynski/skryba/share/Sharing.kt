package com.kuczynski.skryba.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kuczynski.skryba.R
import com.kuczynski.skryba.i18n.str
import java.io.File

/** Sharing through the system sheet (e-mail, WhatsApp, Bluetooth...) and opening in other apps. */
object Sharing {

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun mime(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "txt", "md" -> "text/plain"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "doc" -> "application/msword"
        "xls" -> "application/vnd.ms-excel"
        "ppt" -> "application/vnd.ms-powerpoint"
        "html" -> "text/html"
        else -> "application/octet-stream"
    }

    fun share(context: Context, files: List<File>, subject: String? = null) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uri(context, it) })
        val types = files.map { mime(it) }.distinct()
        val type = if (types.size == 1) types[0] else if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = type
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, str(R.string.share_chooser)))
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, str(R.string.share_text_chooser)))
    }

    fun openExternal(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), mime(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, str(R.string.open_with)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, str(R.string.no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }

    fun qr(text: String, size: Int = 720): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }
}
