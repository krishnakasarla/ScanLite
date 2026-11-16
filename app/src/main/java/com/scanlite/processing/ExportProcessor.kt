package com.scanlite.processing

import com.scanlite.util.ExportFormat
import com.scanlite.util.outputName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

data class SourcePage(val file: File, val rotation: Int = 0)
data class ExportArtifact(val file: File, val name: String, val mime: String, val pageCount: Int) {
    val size: Long get() = file.length()
}
data class PreparedExport(val directory: File, val artifacts: List<ExportArtifact>, val limit: Long?) {
    val size: Long get() = artifacts.sumOf { it.size }
    fun verify() {
        require(artifacts.isNotEmpty())
        artifacts.forEach {
            check(it.file.isFile && it.size > 0) { "Processed output is missing. Prepare the export again." }
            if (limit != null && it.size > limit) throw CompressionFailure()
        }
    }
}
class ExportProcessor {
    suspend fun prepare(pages: List<SourcePage>, format: ExportFormat, limit: Long?, grayscale: Boolean,
        directory: File, base: String, progress: (String) -> Unit): PreparedExport {
        require(pages.isNotEmpty()) { "No pages to export." }
        check(directory.mkdirs() || directory.isDirectory)
        try {
            val artifacts = if (format == ExportFormat.PDF) {
                val file = PdfExporter.export(pages, limit, directory, progress)
                listOf(ExportArtifact(file, outputName(base, format, 0, 1), format.mime, pages.size))
            } else pages.mapIndexed { index, page ->
                currentCoroutineContext().ensureActive()
                progress("Processing page ${index + 1}/${pages.size}")
                val name = outputName(base, format, index, pages.size)
                val file = File(directory, name)
                if ((format == ExportFormat.JPEG || format == ExportFormat.JPG) && page.rotation == 0 &&
                    ImageProcessor.isJpeg(page.file) && (limit == null || page.file.length() <= limit)) {
                    // Retain original JPEG, resolution and EXIF without a lossy re-encode.
                    page.file.copyTo(file)
                } else {
                    val bitmap = ImageProcessor.decode(page.file, page.rotation)
                    try {
                        val bytes = if (format == ExportFormat.PNG) PngCompressor.compress(bitmap, limit, grayscale)
                        else JpegCompressor.compress(bitmap, limit)
                        file.writeBytes(bytes)
                    } finally { bitmap.recycle() }
                }
                ExportArtifact(file, name, format.mime, 1)
            }
            return PreparedExport(directory, artifacts, limit).also { it.verify() }
        } catch (e: Throwable) { directory.deleteRecursively(); throw e }
    }
}
