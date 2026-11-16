package com.scanlite.processing

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

object PdfExporter {
    suspend fun export(pages: List<SourcePage>, limit: Long?, directory: File, progress: (String) -> Unit): File {
        val pdf = File(directory, "document.pdf")
        // Uniform quality/resolution across pages; measure the COMPLETE PDF at each step.
        var edge = 4000
        var attempt = 0
        while (true) {
            for (quality in listOf(100, 90, 80, 65, 45)) {
                currentCoroutineContext().ensureActive()
                attempt++
                val encoded = pages.mapIndexed { index, page ->
                    progress("PDF attempt $attempt · page ${index + 1}/${pages.size}")
                    currentCoroutineContext().ensureActive()
                    val bitmap = ImageProcessor.decode(page.file, page.rotation, edge)
                    try {
                        val file = File(directory, "pdf-page-$index.jpg")
                        file.writeBytes(JpegCompressor.encode(bitmap, quality))
                        RasterPdfWriter.Page(file, bitmap.width, bitmap.height)
                    } finally { bitmap.recycle() }
                }
                RasterPdfWriter.write(encoded, pdf)
                if (limit == null || pdf.length() <= limit) {
                    encoded.forEach { it.jpeg.delete() }
                    return pdf
                }
            }
            if (edge <= 1000) throw CompressionFailure()
            edge = maxOf(1000, (edge * 0.75).toInt())
        }
    }
}
