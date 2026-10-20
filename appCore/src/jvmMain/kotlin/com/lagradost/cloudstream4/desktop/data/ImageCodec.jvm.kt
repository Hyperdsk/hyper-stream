package com.lagradost.cloudstream4.desktop.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun ImageBitmap.encodeJpeg(quality: Int): ByteArray? =
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, quality)?.bytes
