package com.scanlite.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.scanlite.data.local.ScanRecord
import com.scanlite.processing.PreparedExport
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class FileStorageManager(private val context: Context) {
    private val resolver = context.contentResolver

    fun rememberFolder(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    // MediaStore uses pending entries; SAF files are removed if any member of the batch fails.
    // History is committed by the caller only after all saved bytes have been verified.
    fun save(prepared: PreparedExport, tree: Uri?): List<ScanRecord> {
        prepared.verify()
        val created = mutableListOf<Uri>()
        try {
            val folder = tree?.let { DocumentFile.fromTreeUri(context, it) }
            if (tree != null && (folder == null || !folder.canWrite()))
                throw IOException("This folder is no longer writable. Choose a folder again.")
            val records = prepared.artifacts.map { artifact ->
                val uri = if (folder != null) {
                    val name = if (folder.findFile(artifact.name) == null) artifact.name else
                        artifact.name.substringBeforeLast('.') + "_" + UUID.randomUUID().toString().take(8) + "." + artifact.name.substringAfterLast('.')
                    folder.createFile(artifact.mime, name)?.uri ?: throw IOException("Could not create ${artifact.name}.")
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, artifact.mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ScanLite/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Could not create an output file. Check available storage.")
                }
                created.add(uri)
                resolver.openOutputStream(uri, "w")?.use { output -> artifact.file.inputStream().use { it.copyTo(output) } }
                    ?: throw IOException("Could not write the output file.")
                val expected = artifact.file.inputStream().use { stream ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                    hash.digest()
                }
                var actualSize = 0L
                val actual = resolver.openInputStream(uri)?.use { stream ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; actualSize += n; hash.update(buffer, 0, n) }
                    hash.digest()
                } ?: throw IOException("Could not verify the saved file.")
                if (actualSize != artifact.size || !expected.contentEquals(actual) ||
                    (prepared.limit != null && actualSize > prepared.limit))
                    throw IOException("Saved bytes did not match the preview. The export has been removed; retry using another location.")
                val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else artifact.name
                } ?: artifact.name
                ScanRecord(displayName = displayName, createdAt = System.currentTimeMillis(),
                    format = artifact.name.substringAfterLast('.').uppercase(), sizeBytes = actualSize,
                    pageCount = artifact.pageCount, uri = uri.toString())
            }
            if (tree == null) created.forEach { uri ->
                val changed = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                if (changed != 1) throw IOException("Could not publish the saved file.")
            }
            return records
        } catch (e: Throwable) {
            created.forEach { runCatching { deleteUri(it) } }
            throw e
        }
    }

    fun deleteUri(uri: Uri) {
        if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
            if (!android.provider.DocumentsContract.deleteDocument(resolver, uri)) throw IOException("File could not be deleted.")
        } else if (resolver.delete(uri, null, null) != 1) {
            throw IOException("File could not be deleted.")
        }
    }
    fun exists(uri: Uri): Boolean = try {
        resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: java.io.FileNotFoundException) { false }
}
