package com.scanlite

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scanlite.data.local.*
import com.scanlite.processing.*
import com.scanlite.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class ProcessingDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun directory(): File = File(context.cacheDir, "test-${UUID.randomUUID()}").apply { mkdirs() }
    private fun noisy(w: Int = 1200, h: Int = 1600): Bitmap {
        val random = Random(42)
        val pixels = IntArray(w * h) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
    private fun document(color: Int = Color.WHITE): Bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.BLACK; textSize = 35f }
        repeat(30) { row -> canvas.drawText("ScanLite synthetic document line ${row + 1}", 60f, 100f + row * 46f, paint) }
    }
    private fun source(directory: File, name: String, color: Int): SourcePage {
        val bitmap = document(color)
        val file = File(directory, name)
        try { file.writeBytes(JpegCompressor.encode(bitmap, 100)) } finally { bitmap.recycle() }
        return SourcePage(file)
    }
    @Test fun jpegValidConstrainedAndAspectRatio() = runBlocking {
        val image = document()
        try {
            val bytes = JpegCompressor.compress(image, 100 * 1024L)
            assertTrue(bytes.size <= 100 * 1024)
            assertEquals(0xff, bytes[0].toInt() and 0xff)
            assertEquals(0xd8, bytes[1].toInt() and 0xff)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(decoded)
            try { assertEquals(image.width.toDouble() / image.height, decoded.width.toDouble() / decoded.height, 0.002) }
            finally { decoded.recycle() }
        } finally { image.recycle() }
    }
    @Test fun jpegImpossibleFails() = runBlocking {
        val bitmap = noisy()
        try {
            try { JpegCompressor.compress(bitmap, 1); fail("Oversized output must never be accepted") }
            catch (_: CompressionFailure) { }
        } finally { bitmap.recycle() }
    }
    @Test fun pngRemainsPng() = runBlocking {
        val image = document()
        try {
            val bytes = PngCompressor.compress(image, null, false)
            assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), bytes.take(8).toByteArray())
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(decoded)
            assertEquals(image.width, decoded.width)
            assertEquals(image.height, decoded.height)
            decoded.recycle()
        } finally { image.recycle() }
    }
    @Test fun pngImpossibleFails() = runBlocking {
        val image = noisy()
        try {
            try { PngCompressor.compress(image, 1, true); fail("Expected compression failure") }
            catch (_: CompressionFailure) { }
        } finally { image.recycle() }
    }
    @Test fun pngAchievableSizeVerified() = runBlocking {
        val image = document()
        try { assertTrue(PngCompressor.compress(image, 300 * 1024L, true).size <= 300 * 1024) }
        finally { image.recycle() }
    }
    @Test fun unlimitedJpegKeepsExactSourceBytes() = runBlocking {
        val dir = directory()
        try {
            val page = source(dir, "original.jpg", Color.WHITE)
            val output = ExportProcessor().prepare(listOf(page), ExportFormat.JPEG, null, false,
                File(dir, "export"), "test") { }
            assertArrayEquals(page.file.readBytes(), output.artifacts.single().file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rotationsAppliedAndAspectRetained() {
        val dir = directory()
        try {
            val page = source(dir, "source.jpg", Color.WHITE)
            val rotated = ImageProcessor.decode(page.file, 90)
            try { assertEquals(1600, rotated.width); assertEquals(1200, rotated.height) }
            finally { rotated.recycle() }
        } finally { dir.deleteRecursively() }
    }
    private fun assertPdf(file: File, expected: Int) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { assertEquals(expected, it.pageCount) }
        }
    }
    @Test fun singlePagePdf() = runBlocking {
        val dir = directory()
        try {
            val page = source(dir, "source.jpg", Color.WHITE)
            val pdf = PdfExporter.export(listOf(page), null, dir) { }
            assertPdf(pdf, 1)
        } finally { dir.deleteRecursively() }
    }
    @Test fun multipagePdfPreservesCountAndOrder() = runBlocking {
        val dir = directory()
        try {
            val pages = listOf(source(dir, "red.jpg", Color.RED), source(dir, "blue.jpg", Color.BLUE))
            val pdf = PdfExporter.export(pages, null, dir) { }
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    assertEquals(2, renderer.pageCount)
                    repeat(2) { index -> renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pixel = bitmap.getPixel(10, 10)
                            if (index == 0) assertTrue(Color.red(pixel) > Color.blue(pixel) + 150)
                            else assertTrue(Color.blue(pixel) > Color.red(pixel) + 150)
                        } finally { bitmap.recycle() }
                    } }
                }
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun pdfMeasuresWholeFileAndRespectsAchievableLimit() = runBlocking {
        val dir = directory()
        try {
            val pages = listOf(source(dir, "first.jpg", Color.WHITE), source(dir, "second.jpg", Color.WHITE))
            val limit = 180 * 1024L
            val pdf = PdfExporter.export(pages, limit, dir) { }
            assertTrue(pdf.length() <= limit)
            assertPdf(pdf, 2)
            val allBytes = pdf.readBytes()
            assertEquals(pdf.length(), allBytes.size.toLong())
        } finally { dir.deleteRecursively() }
    }
    @Test fun pdfImpossibleFails() = runBlocking {
        val dir = directory()
        try {
            val page = source(dir, "source.jpg", Color.WHITE)
            try { PdfExporter.export(listOf(page), 1, dir) { }; fail("Expected strict limit failure") }
            catch (_: CompressionFailure) { }
        } finally { dir.deleteRecursively() }
    }
    @Test fun metadataAccuratelyStored() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val record = ScanRecord(id = 42, displayName = "scan.jpeg", createdAt = 12345,
                format = "JPEG", sizeBytes = 102400, pageCount = 1, uri = "content://test/document/42")
            db.scans().insert(listOf(record))
            assertEquals(record, db.scans().get(42))
        } finally { db.close() }
    }
    @Test fun preparedOutputCannotAcceptOversizedBytes() {
        val dir = directory()
        try {
            val file = File(dir, "test.jpg").apply { writeBytes(ByteArray(1025)) }
            val output = PreparedExport(dir, listOf(ExportArtifact(file, "test.jpg", "image/jpeg", 1)), 1024)
            assertThrows(CompressionFailure::class.java) { output.verify() }
        } finally { dir.deleteRecursively() }
    }
}
