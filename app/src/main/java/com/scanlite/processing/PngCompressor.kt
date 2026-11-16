package com.scanlite.processing

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

object PngCompressor {
    private fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed." }
        it.toByteArray()
    }
    suspend fun compress(source: Bitmap, limit: Long?, grayscale: Boolean): ByteArray {
        val original = if (grayscale) ImageProcessor.grayscale(source) else source
        var bitmap = original
        val floor = minimumEdge(source)
        try {
            repeat(12) {
                currentCoroutineContext().ensureActive()
                val bytes = encode(bitmap)
                if (limit == null || bytes.size.toLong() <= limit) return bytes
                val edge = maxOf(bitmap.width, bitmap.height)
                if (edge <= floor) throw CompressionFailure()
                val next = ImageProcessor.scaled(bitmap, maxOf(0.8, floor.toDouble() / edge))
                if (bitmap !== original) bitmap.recycle()
                bitmap = next
            }
            throw CompressionFailure()
        } finally {
            if (bitmap !== original) bitmap.recycle()
            if (original !== source) original.recycle()
        }
    }
}
