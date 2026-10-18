package com.harsh.peakbud.feature.panorama

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Saves the drawn skyline with a caption strip ("PeakBud · place · direction · date") and opens the share sheet. */
internal suspend fun shareView(context: Context, image: ImageBitmap, caption: String) {
    val file = withContext(Dispatchers.IO) {
        // The captured layer may be a hardware bitmap; copy it so it can be drawn into a software canvas.
        val view = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val strip = (view.width * .09f).toInt()
        val out = Bitmap.createBitmap(view.width, view.height + strip, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(0xFF1F3A2E.toInt())
            drawBitmap(view, 0f, 0f, null)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = strip * .36f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            val pad = strip * .35f
            val text = android.text.TextUtils.ellipsize(caption, android.text.TextPaint(paint), view.width - pad * 2, android.text.TextUtils.TruncateAt.END).toString()
            drawText(text, pad, view.height + strip / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        }
        view.recycle()
        File(context.cacheDir, "shared").apply { mkdirs() }.resolve("peakbud-view.png").also { f -> f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }; out.recycle() }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, caption)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
