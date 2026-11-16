package com.scanlite.scanner

import android.app.Activity
import android.content.IntentSender
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning

class DocumentScannerManager {
    fun start(activity: Activity, ready: (IntentSender) -> Unit, failure: (String) -> Unit) {
        val availability = GoogleApiAvailability.getInstance()
        val status = availability.isGooglePlayServicesAvailable(activity)
        if (status != ConnectionResult.SUCCESS) {
            failure("Google Play services is unavailable or needs updating (${availability.getErrorString(status)}). Update it to scan, or import images from the gallery.")
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        try {
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { ready(it) }
                .addOnFailureListener {
                    failure("Scanner could not start. First use requires Google Play services to download scanner components; connect to the internet and retry. Your device also needs at least 1.7 GB RAM. ${it.localizedMessage.orEmpty()}")
                }
        } catch (e: Exception) { failure("Scanner could not start: ${e.localizedMessage}") }
    }
}
