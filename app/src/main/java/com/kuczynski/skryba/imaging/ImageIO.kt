package com.kuczynski.skryba.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.kuczynski.skryba.R
import com.kuczynski.skryba.i18n.str
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageIO {

    /** Dekoduje obraz z uwzględnieniem orientacji EXIF i ograniczeniem dłuższego boku do [maxSide]. */
    fun decode(file: File, maxSide: Int): Bitmap {
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        return decodeInternal({ file.inputStream() }, exif, maxSide)
    }

    fun decodeUri(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val exif = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
        }.getOrNull()
        return decodeInternal({ context.contentResolver.openInputStream(uri)!! }, exif, maxSide)
    }

    fun decodeBytes(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
        decodeInternal({ bytes.inputStream() }, runCatching { ExifInterface(bytes.inputStream()) }.getOrNull(), maxSide)
    }.getOrNull()

    private fun decodeInternal(open: () -> InputStream, exif: ExifInterface?, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { str(R.string.err_read_image) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = open().use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error(str(R.string.err_read_image))
        val longest = max(bmp.width, bmp.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            bmp = scaled(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt())
        }
        if (exif != null) {
            val rot = exif.rotationDegrees
            val flip = exif.isFlipped
            if (rot != 0 || flip) {
                val m = Matrix()
                if (flip) m.postScale(-1f, 1f)
                m.postRotate(rot.toFloat())
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                bmp = r
            }
        }
        return bmp
    }

    fun scaled(src: Bitmap, w: Int, h: Int): Bitmap {
        val r = Bitmap.createScaledBitmap(src, max(1, w), max(1, h), true)
        if (r !== src) src.recycle()
        return r
    }

    fun limit(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 90) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun savePng(bmp: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Wymiary obrazu po uwzględnieniu EXIF, bez dekodowania pikseli. */
    fun size(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        val rot = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        return if (rot == 90 || rot == 270) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }
}
