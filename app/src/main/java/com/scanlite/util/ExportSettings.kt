package com.scanlite.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ExportFormat(val extension: String, val mime: String) {
    JPEG("jpeg", "image/jpeg"), JPG("jpg", "image/jpeg"), PNG("png", "image/png"), PDF("pdf", "application/pdf")
}
enum class SizeUnit(val bytes: Long) { KB(1024), MB(1_048_576) }

object SizeLimit {
    fun parse(text: String, unit: SizeUnit): Long? {
        if (text.isBlank()) return null
        require(text.length <= 64) { "Maximum size is too long. Enter a sensible positive number." }
        val number = text.trim().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Enter a positive number, such as 100 or 0.5.")
        require(number > BigDecimal.ZERO) { "Maximum size must be greater than zero." }
        val exact = number.multiply(BigDecimal.valueOf(unit.bytes))
        require(exact >= BigDecimal.ONE && exact <= BigDecimal.valueOf(Long.MAX_VALUE)) {
            "Choose a size of at least one byte and within the supported numeric range."
        }
        val bytes = exact.setScale(0, RoundingMode.FLOOR)
        return bytes.longValueExact()
    }
}
fun displaySize(bytes: Long): String = if (bytes < 1_048_576)
    String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
else String.format(Locale.getDefault(), "%.2f MB", bytes / 1_048_576.0)

fun exportBaseName(): String = "ScanLite_" +
    SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + "_" + UUID.randomUUID().toString().take(8)

fun outputName(base: String, format: ExportFormat, index: Int, count: Int): String =
    base + (if (count > 1 && format != ExportFormat.PDF) "_page_%03d".format(Locale.US, index + 1) else "") + "." + format.extension
