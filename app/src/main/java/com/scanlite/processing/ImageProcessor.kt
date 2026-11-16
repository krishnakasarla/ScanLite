package com.scanlite.processing

import android.graphics.*
import java.io.File
import kotlin.math.roundToInt

object ImageProcessor {
    // ImageDecoder applies EXIF orientation, including mirrored orientations.
    // Reserve room for decode, rotation, encoding and UI; cap a page at 12 MP / 4000 px.
    fun decode(file: File, rotation: Int = 0, edge: Int = 4000): Bitmap {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val pixels = minOf(12_000_000.0, Runtime.getRuntime().maxMemory() / 32.0)
            val scale = minOf(1.0, edge.toDouble() / maxOf(w, h), kotlin.math.sqrt(pixels / (w.toDouble() * h)))
            decoder.setTargetSize(maxOf(1, (w * scale).roundToInt()), maxOf(1, (h * scale).roundToInt()))
        }
        val rgb = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(rgb).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG)) }
        decoded.recycle()
        if (rotation % 360 == 0) return rgb
        val transformed = Bitmap.createBitmap(rgb, 0, 0, rgb.width, rgb.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (transformed !== rgb) rgb.recycle()
        return transformed
    }

    fun scaled(source: Bitmap, scale: Double): Bitmap = Bitmap.createScaledBitmap(source,
        maxOf(1, (source.width * scale).roundToInt()), maxOf(1, (source.height * scale).roundToInt()), true)

    fun grayscale(source: Bitmap): Bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(it).drawBitmap(source, 0f, 0f, paint)
    }

    fun isJpeg(file: File): Boolean = file.inputStream().use {
        it.read() == 0xff && it.read() == 0xd8 && it.read() == 0xff
    }
}

class CompressionFailure : Exception("Unable to compress this document below the selected size without significantly reducing readability. Please choose a larger size.")

// Safety floor, not a promise of legibility for every document. Users review the exact output.
fun minimumEdge(bitmap: Bitmap): Int = minOf(1000, maxOf(bitmap.width, bitmap.height))
