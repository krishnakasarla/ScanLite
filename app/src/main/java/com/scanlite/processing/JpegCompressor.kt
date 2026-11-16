package com.scanlite.processing

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

object JpegCompressor {
    fun encode(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "JPEG encoding failed." }
        it.toByteArray()
    }

    suspend fun compress(source: Bitmap, limit: Long?): ByteArray {
        if (limit == null) return encode(source, 100)
        val floor = minimumEdge(source)
        var bitmap = source
        try {
            repeat(12) {
                currentCoroutineContext().ensureActive()
                val high = encode(bitmap, 100)
                if (high.size.toLong() <= limit) return high
                val low = encode(bitmap, 45)
                if (low.size.toLong() <= limit) {
                    var best = low
                    var left = 46
                    var right = 99
                    while (left <= right) {
                        currentCoroutineContext().ensureActive()
                        val mid = (left + right) / 2
                        val candidate = encode(bitmap, mid)
                        if (candidate.size.toLong() <= limit) { best = candidate; left = mid + 1 }
                        else right = mid - 1
                    }
                    return best
                }
                val edge = maxOf(bitmap.width, bitmap.height)
                if (edge <= floor) throw CompressionFailure()
                val next = ImageProcessor.scaled(bitmap, maxOf(0.8, floor.toDouble() / edge))
                if (bitmap !== source) bitmap.recycle()
                bitmap = next
            }
            throw CompressionFailure()
        } finally { if (bitmap !== source) bitmap.recycle() }
    }
}
