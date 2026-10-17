package com.screencal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** Офлайн-распознавание текста (Tesseract, русский + английский). Работает без интернета. */
object Ocr {

    private fun ensureData(ctx: Context): File {
        val base = File(ctx.filesDir, "tesseract")
        val dir = File(base, "tessdata").apply { mkdirs() }
        for (name in listOf("rus.traineddata", "eng.traineddata")) {
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                ctx.assets.open("tessdata/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        return base
    }

    fun recognize(ctx: Context, src: Bitmap): String {
        val base = ensureData(ctx)
        val tess = TessBaseAPI()
        try {
            if (!tess.init(base.absolutePath, "rus+eng")) error("Не удалось запустить Tesseract")
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setVariable("preserve_interword_spaces", "1")
            tess.setImage(prepare(src))
            return tess.getUTF8Text() ?: ""
        } finally {
            tess.recycle()
        }
    }

    /** Серый цвет, увеличение мелких картинок, инверсия тёмной темы. */
    private fun prepare(src: Bitmap): Bitmap {
        val scale = if (src.width < 1000) 2f else 1f
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        if (isDark(src)) {
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, null, Rect(0, 0, w, h), paint)
        return out
    }

    private fun isDark(b: Bitmap): Boolean {
        var sum = 0L
        var n = 0
        val stepX = maxOf(1, b.width / 40)
        val stepY = maxOf(1, b.height / 40)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                sum += (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                n++
                x += stepX
            }
            y += stepY
        }
        return n > 0 && sum / n < 110
    }
}
