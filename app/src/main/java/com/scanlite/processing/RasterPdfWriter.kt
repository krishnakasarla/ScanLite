package com.scanlite.processing

import java.io.File
import java.io.OutputStream
import java.util.Locale

/** Minimal PDF 1.4 writer for RGB baseline JPEG pages. No JavaScript, attachments or external resources.
 * Embeds the exact JPEG stream through DCTDecode, avoiding PdfDocument's bitmap re-encoding.
 * Streams page images from disk; memory and byte accounting do not grow with page count.
 */
object RasterPdfWriter {
    data class Page(val jpeg: File, val width: Int, val height: Int)

    fun write(pages: List<Page>, destination: File) {
        require(pages.isNotEmpty())
        destination.outputStream().buffered().use { raw ->
            val out = CountingOutput(raw)
            val offsets = mutableListOf(0L)
            fun text(value: String) = out.write(value.toByteArray(Charsets.ISO_8859_1))
            fun obj(id: Int, body: () -> Unit) {
                check(offsets.size == id)
                offsets.add(out.count)
                text("$id 0 obj\n"); body(); text("\nendobj\n")
            }
            text("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
            obj(1) { text("<< /Type /Catalog /Pages 2 0 R >>") }
            obj(2) {
                val kids = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
                text("<< /Type /Pages /Count ${pages.size} /Kids [ $kids ] >>")
            }
            pages.forEachIndexed { index, page ->
                require(page.width > 0 && page.height > 0)
                val id = 3 + index * 3
                val scale = minOf(595.0 / page.width, 842.0 / page.height)
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                obj(id) { text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Resources << /XObject << /Im0 ${id + 1} 0 R >> >> /Contents ${id + 2} 0 R >>") }
                obj(id + 1) {
                    text("<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.length()} >>\nstream\n")
                    page.jpeg.inputStream().use { it.copyTo(out) }
                    text("\nendstream")
                }
                val content = "q\n$w 0 0 $h 0 0 cm\n/Im0 Do\nQ\n"
                obj(id + 2) { text("<< /Length ${content.length} >>\nstream\n${content}endstream") }
            }
            val start = out.count
            text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
            offsets.drop(1).forEach { text(String.format(Locale.US, "%010d 00000 n \n", it)) }
            text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n")
        }
    }
    private class CountingOutput(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { delegate.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len); count += len }
    }
}
