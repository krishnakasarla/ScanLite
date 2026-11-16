import com.scanlite.util.*
import com.scanlite.processing.RasterPdfWriter
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    var passed = 0
    fun test(name: String, body: () -> Unit) { body(); passed++; println("PASS $name") }
    test("KB conversion") { check(SizeLimit.parse("100", SizeUnit.KB) == 102400L) }
    test("MB conversion") { check(SizeLimit.parse("2", SizeUnit.MB) == 2097152L) }
    test("decimal size rounds down") { check(SizeLimit.parse("0.1", SizeUnit.KB) == 102L) }
    test("unlimited size") { check(SizeLimit.parse("  ", SizeUnit.MB) == null) }
    test("invalid size inputs") {
        listOf("0", "-1", "NaN", "Infinity", "abc", "0.00001", "99999999999999999999").forEach { input ->
            check(runCatching { SizeLimit.parse(input, SizeUnit.KB) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    test("JPG and JPEG MIME") { check(ExportFormat.JPEG.mime == "image/jpeg" && ExportFormat.JPG.mime == "image/jpeg") }
    test("PNG and PDF MIME") { check(ExportFormat.PNG.mime == "image/png" && ExportFormat.PDF.mime == "application/pdf") }
    test("all single page extensions") { ExportFormat.entries.forEach { check(outputName("scan", it, 0, 1) == "scan.${it.extension}") } }
    test("numbered multipage images") { check(outputName("scan", ExportFormat.JPEG, 1, 3) == "scan_page_002.jpeg") }
    test("multipage PDF filename") { check(outputName("scan", ExportFormat.PDF, 0, 4) == "scan.pdf") }
    test("collision resistant names") { check((1..1000).map { exportBaseName() }.toSet().size == 1000) }
    val dir = File(args[0]).apply { mkdirs() }
    val pages = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
        val image = BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, 600, 800); dispose() }
        val file = File(dir, "page-$index.jpg")
        check(ImageIO.write(image, "jpeg", file))
        RasterPdfWriter.Page(file, 600, 800)
    }
    test("PDF rejects empty document") { check(runCatching { RasterPdfWriter.write(emptyList(), File(dir, "empty.pdf")) }.exceptionOrNull() is IllegalArgumentException) }
    for (count in 1..2) test("${count}-page PDF structure and exact stream sizes") {
        val file = File(dir, "test-$count.pdf")
        RasterPdfWriter.write(pages.take(count), file)
        val bytes = file.readBytes()
        val content = bytes.toString(Charsets.ISO_8859_1)
        check(content.startsWith("%PDF-1.4"))
        check(content.endsWith("%%EOF\n"))
        check(content.contains("/Count $count"))
        val start = content.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        check(content.substring(start).startsWith("xref\n"))
        val xref = content.substring(start).lines()
        val objects = xref[1].substringAfter(' ').toInt()
        check(objects == 3 + count * 3)
        for (id in 1 until objects) {
            val offset = xref[id + 2].substringBefore(' ').toInt()
            check(content.substring(offset).startsWith("$id 0 obj\n"))
        }
        Regex("/Length (\\d+) >>\\nstream\\n").findAll(content).forEach { match ->
            val begin = match.range.last + 1
            val length = match.groupValues[1].toInt()
            val end = content.indexOf("endstream", begin)
            // Binary JPEG streams have an extra PDF stream-delimiter newline.
            if (content.substring(maxOf(0, match.range.first - 60), match.range.first).contains("DCTDecode")) {
                check(end - begin - 1 == length)
            } else check(end - begin == length)
        }
        pages.take(count).forEach { page -> check(content.contains(page.jpeg.readBytes().toString(Charsets.ISO_8859_1))) }
    }
    println("$passed core checks passed. Android encoders, Compose and Room require the device suite.")
}
