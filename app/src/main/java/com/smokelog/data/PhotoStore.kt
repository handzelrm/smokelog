package com.smokelog.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Cook photos as JPEGs in filesDir/photos, resized so a long cook's pictures don't fill the phone. */
class PhotoStore(private val context: Context) {
    val dir: File = File(context.filesDir, "photos").apply { mkdirs() }

    fun file(fileName: String) = File(dir, fileName)

    /** A temporary file + content Uri for the camera app to write a full-size picture into. */
    fun newCameraTarget(): Pair<File, Uri> {
        val camDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val f = File(camDir, "capture-${System.currentTimeMillis()}.jpg")
        return f to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    /** Copies an image (camera capture or library pick) into the photo store; returns the stored file name. */
    suspend fun importImage(source: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // Read the bounds first so huge photos are decoded at a reduced size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Couldn't read that image")

        val rotation = resolver.openInputStream(source)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val bitmap = if (matrix.isIdentity) decoded
            else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val name = "${UUID.randomUUID()}.jpg"
        file(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        name
    }

    /** Stores JPEG bytes received in a shared cook file. */
    suspend fun importBytes(jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val name = "${UUID.randomUUID()}.jpg"
        file(name).writeBytes(jpeg)
        name
    }

    fun delete(fileName: String) { file(fileName).delete() }

    fun shareUri(fileName: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file(fileName))

    companion object { const val MAX_EDGE = 2048 }
}
