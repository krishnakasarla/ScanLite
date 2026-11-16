package com.scanlite

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.viewModels
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanlite.scanner.DocumentScannerManager
import com.scanlite.ui.ScanLiteApp
import com.scanlite.ui.ScanViewModel

class MainActivity : ComponentActivity() {
    private val vm: ScanViewModel by viewModels()
    private val manager = DocumentScannerManager()
    private val scanner = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.scannerFinished()
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty()
            if (pages.isEmpty()) vm.message("The scanner returned no pages. Please scan again or import images.")
            else vm.importPages(pages.map { it.imageUri })
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) vm.importPages(uris)
    }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> vm.selectFolder(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScanLiteApp(vm,
                scan = { launchScanner() },
                gallery = {
                    try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    catch (e: Exception) { vm.message("The image picker could not open: ${e.localizedMessage}. Try importing through the scanner editor.") }
                },
                chooseFolder = {
                    try { folder.launch(vm.state.value.folder?.let(Uri::parse)) }
                    catch (e: Exception) { vm.message("The folder picker could not open: ${e.localizedMessage}. You can still save to Downloads.") }
                },
                openOrShare = { uri, mime, share ->
                    try {
                        val intent = if (share) Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(contentResolver, "ScanLite document", uri)
                        } else Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime) }
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(if (share) Intent.createChooser(intent, "Share scan") else intent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        vm.message("No installed app can open this file format. Install a compatible viewer or share the file.")
                    } catch (e: Exception) { vm.message("Could not open this file: ${e.localizedMessage}") }
                })
        }
    }
    private fun launchScanner() {
        vm.scannerLoading()
        manager.start(this, ready = { intentSender ->
            if (isDestroyed || isFinishing) { vm.scannerFinished(); return@start }
            try { scanner.launch(IntentSenderRequest.Builder(intentSender).build()) }
            catch (e: Exception) { vm.message("Could not launch the scanner. Please try again. ${e.localizedMessage.orEmpty()}") }
        }, failure = { vm.message(it) })
    }
}
