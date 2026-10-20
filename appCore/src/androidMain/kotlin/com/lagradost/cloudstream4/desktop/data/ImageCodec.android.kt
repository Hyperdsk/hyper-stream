package com.lagradost.cloudstream4.desktop.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual fun ImageBitmap.encodeJpeg(quality: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    return if (asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
}
