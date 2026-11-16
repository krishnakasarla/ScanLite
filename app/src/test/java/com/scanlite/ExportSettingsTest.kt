package com.scanlite

import com.scanlite.util.*
import org.junit.Assert.*
import org.junit.Test

class ExportSettingsTest {
    @Test fun binaryUnits() {
        assertEquals(1024L, SizeLimit.parse("1", SizeUnit.KB))
        assertEquals(1_048_576L, SizeLimit.parse("1", SizeUnit.MB))
        assertEquals(524_288L, SizeLimit.parse("0.5", SizeUnit.MB))
        assertEquals(102L, SizeLimit.parse("0.1", SizeUnit.KB))
    }
    @Test fun emptyIsUnlimited() { assertNull(SizeLimit.parse(" ", SizeUnit.KB)) }
    @Test fun invalidInput() {
        listOf("0", "-1", "hello", "NaN", "Infinity", "0.000001", "999999999999999999999").forEach {
            assertThrows(IllegalArgumentException::class.java) { SizeLimit.parse(it, SizeUnit.KB) }
        }
    }
    @Test fun jpegAndJpgMimeTypes() {
        assertEquals("image/jpeg", ExportFormat.JPEG.mime)
        assertEquals("image/jpeg", ExportFormat.JPG.mime)
        assertEquals("image/png", ExportFormat.PNG.mime)
        assertEquals("application/pdf", ExportFormat.PDF.mime)
    }
    @Test fun extensionsAndPageNames() {
        assertEquals("ScanLite_test.jpeg", outputName("ScanLite_test", ExportFormat.JPEG, 0, 1))
        assertEquals("ScanLite_test_page_002.jpg", outputName("ScanLite_test", ExportFormat.JPG, 1, 3))
        assertEquals("ScanLite_test_page_001.png", outputName("ScanLite_test", ExportFormat.PNG, 0, 2))
        assertEquals("ScanLite_test.pdf", outputName("ScanLite_test", ExportFormat.PDF, 0, 3))
    }
    @Test fun namesAreUnique() { assertEquals(100, (1..100).map { exportBaseName() }.toSet().size) }
}
