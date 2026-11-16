package com.scanlite.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.scanlite.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanlite.data.local.ScanRecord
import com.scanlite.processing.ImageProcessor
import com.scanlite.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val palette = lightColorScheme(primary = Color(0xFF195BCA), secondary = Color(0xFF48617D),
    background = Color(0xFFF6F8FC), surface = Color.White, primaryContainer = Color(0xFFDFEAFE))
private val darkPalette = darkColorScheme(primary = Color(0xFFABC7FF), onPrimary = Color(0xFF002F6C),
    secondary = Color(0xFFB4C8E2), background = Color(0xFF101721), surface = Color(0xFF18212D),
    surfaceVariant = Color(0xFF414B5B), primaryContainer = Color(0xFF16427D), onPrimaryContainer = Color(0xFFD9E2FF))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanLiteApp(vm: ScanViewModel, scan: () -> Unit, gallery: () -> Unit, chooseFolder: () -> Unit,
    openOrShare: (Uri, String, Boolean) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(preferences.getString("theme", "system") ?: "system") }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(s.notice) {
        val notice = s.notice ?: return@LaunchedEffect
        try { snackbarHostState.showSnackbar(notice) } finally { vm.clearNotice() }
    }
    val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    var discardDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = s.screen != "home") {
        if (!s.busy) vm.navigate("home")
    }
    MaterialTheme(colorScheme = if (dark) darkPalette else palette) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = { TopAppBar(title = { Text(when (s.screen) { "preview" -> "Preview & export"; "history" -> "History"; "settings" -> "Settings"; else -> "ScanLite" },
                fontWeight = FontWeight.Bold) }, navigationIcon = {
                if (s.screen == "settings") TextButton(onClick = { vm.navigate("home") }) { Text("Back") }
            }, actions = {
                if (s.screen == "preview") TextButton(onClick = { discardDialog = true }, enabled = !s.busy) { Text("Discard") }
                if (s.screen == "home") IconButton(onClick = { vm.navigate("settings") }, enabled = !s.busy) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                }
            }) },
            bottomBar = {
                if (s.screen != "settings") NavigationBar {
                    NavigationBarItem(selected = s.screen == "home", onClick = { if (!s.busy) vm.navigate("home") },
                        icon = { NavMark("home") }, label = { Text("Home") })
                    if (s.pages.isNotEmpty()) NavigationBarItem(selected = s.screen == "preview", onClick = { if (!s.busy) vm.navigate("preview") },
                        icon = { NavMark("preview") }, label = { Text("Preview") })
                    NavigationBarItem(selected = s.screen == "history", onClick = { if (!s.busy) vm.navigate("history") },
                        icon = { NavMark("history") }, label = { Text("History") })
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (s.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(s.progress, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
                }
                s.error?.let { error ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(error)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = vm::clearMessage) { Text("Dismiss") }
                                if (s.screen == "preview") TextButton(onClick = { vm.maximum("") }) { Text("Remove limit") }
                            }
                        }
                    }
                }
                when (s.screen) {
                    "preview" -> PreviewScreen(s, vm, scan)
                    "settings" -> SettingsScreen(s, vm, themeMode, { value ->
                        themeMode = value
                        preferences.edit().putString("theme", value).apply()
                    }, chooseFolder)
                    "history" -> HistoryScreen(history, vm, openOrShare)
                    else -> HomeScreen(s, history, vm, scan, gallery, openOrShare)
                }
            }
        }
        if (discardDialog) AlertDialog(onDismissRequest = { discardDialog = false },
            title = { Text("Discard this scan?") }, text = { Text("Unsaved pages and the processed preview will be removed.") },
            confirmButton = { TextButton(onClick = { discardDialog = false; vm.discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("Keep") } })
    }
}

@Composable
private fun HomeScreen(s: ScanState, records: List<ScanRecord>, vm: ScanViewModel, scan: () -> Unit,
    gallery: () -> Unit, action: (Uri, String, Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your documents, ready to go.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Scan a document or import a photo. Save it in the format you need.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = scan, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("Scan Document") }
                    OutlinedButton(onClick = gallery, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Import from Gallery") }
                }
            }
        }
        if (s.pages.isNotEmpty()) item {
            OutlinedButton(onClick = { vm.navigate("preview") }, modifier = Modifier.fillMaxWidth(), enabled = !s.busy) { Text("Continue current scan · ${s.pages.size} pages") }
        }
        item { Text("Recent scans", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (records.isEmpty()) item { EmptyHistory() }
        items(records.take(5), key = { it.id }) { HistoryCard(it, vm, action) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun PreviewScreen(s: ScanState, vm: ScanViewModel, rescan: () -> Unit) {
    if (s.pages.isEmpty()) { Text("No pages. Scan or import a document to begin.", Modifier.padding(24.dp)); return }
    val output = s.prepared
    val processed = output?.artifacts?.getOrNull(if (s.format == ExportFormat.PDF) 0 else s.pageIndex)
    val page = s.pages[s.pageIndex]
    var formatMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(if (processed == null) "Your document" else "Ready to save", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text("${s.pageIndex + 1} / ${s.pages.size}", style = MaterialTheme.typography.labelLarge)
            }
            FilePreview(processed?.file ?: page.file, rotation = if (processed == null) page.rotation else 0,
                pdfPage = if (s.format == ExportFormat.PDF && processed != null) s.pageIndex else null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 300.dp).height(280.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.page(s.pageIndex - 1) }, enabled = s.pageIndex > 0 && !s.busy) { Text("Previous page") }
                TextButton(onClick = { vm.page(s.pageIndex + 1) }, enabled = s.pageIndex < s.pages.lastIndex && !s.busy) { Text("Next page") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { vm.rotate(-90) }, enabled = !s.busy) { Text("Rotate left") }
                OutlinedButton(onClick = { vm.rotate(90) }, enabled = !s.busy) { Text("Rotate right") }
            }
            if (s.pages.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.pages.size) { index -> FilterChip(selected = index == s.pageIndex, onClick = { vm.page(index) }, enabled = !s.busy, label = { Text("Page ${index + 1}") }) }
            }
            HorizontalDivider()
            Text("Format", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ExposedDropdownMenuBox(expanded = formatMenu, onExpandedChange = { if (!s.busy) formatMenu = it }) {
                OutlinedTextField(value = s.format.name, onValueChange = {}, readOnly = true,
                    enabled = !s.busy, modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatMenu) },
                    singleLine = true)
                ExposedDropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                    ExportFormat.entries.forEach { format ->
                        DropdownMenuItem(text = { Text(format.name) }, onClick = { vm.format(format); formatMenu = false })
                    }
                }
            }
            Text("Maximum size", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(s.maximum, vm::maximum, placeholder = { Text("Optional") },
                    singleLine = true, enabled = !s.busy, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                SizeUnit.entries.forEach { unit -> FilterChip(selected = s.unit == unit, onClick = { vm.unit(unit) }, enabled = !s.busy, label = { Text(unit.name) }) }
            }
            if (s.maximum.isNotBlank()) Text(if (s.format == ExportFormat.PDF) "Limit applies to the entire PDF" else "Limit applies to each image", style = MaterialTheme.typography.bodySmall)
            if (s.format == ExportFormat.PNG) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(s.grayscale, vm::grayscale, enabled = !s.busy); Text("Grayscale")
            }
            TextButton(onClick = rescan, enabled = !s.busy) { Text("Scan again") }
            if (output != null) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Ready to save", fontWeight = FontWeight.Bold)
                        Text("${s.format.name} · ${s.pages.size} ${if (s.pages.size == 1) "page" else "pages"} · ${displaySize(output.size)} total")
                        output.limit?.let { Text("Maximum: ${displaySize(it)} ${if (s.format == ExportFormat.PDF) "per PDF" else "per image"}", style = MaterialTheme.typography.bodySmall) }
                        if (output.artifacts.size == 1) Text(output.artifacts.first().name, style = MaterialTheme.typography.bodySmall)
                        else Text("${output.artifacts.size} files will be saved", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Button(onClick = if (output == null) vm::prepare else vm::save, enabled = !s.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (output == null) "Prepare preview" else "Save files")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(s: ScanState, vm: ScanViewModel, themeMode: String, onTheme: (String) -> Unit, chooseFolder: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "Unknown"
    }
    var themeExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(expanded = themeExpanded, onExpandedChange = { themeExpanded = it }) {
            val selected = when (themeMode) { "light" -> "Light"; "dark" -> "Dark"; else -> "System default" }
            OutlinedTextField(value = selected, onValueChange = {}, readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = themeExpanded) }, singleLine = true)
            ExposedDropdownMenu(expanded = themeExpanded, onDismissRequest = { themeExpanded = false }) {
                listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onTheme(value); themeExpanded = false })
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Default save location", style = MaterialTheme.typography.labelLarge)
        Text(if (s.folder == null) "Downloads / ScanLite" else Uri.parse(s.folder).lastPathSegment ?: "Selected folder", style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = chooseFolder, enabled = !s.busy) { Text("Choose folder") }
            if (s.folder != null) TextButton(onClick = vm::defaultFolder, enabled = !s.busy) { Text("Use Downloads") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("About ScanLite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Version $version", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("ScanLite has no account, analytics or cloud storage. Your chosen folder may be synced by its storage provider; shared files are handled by the app you choose.", style = MaterialTheme.typography.bodyMedium)
        Text("An unchanged JPEG may retain its original metadata, including location if present. The scanner uses Google Play services, which may download scanner components.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun NavMark(kind: String) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp).semantics { contentDescription = "" }) {
        val w = size.width; val h = size.height; val stroke = 2.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(w*x1, h*y1), Offset(w*x2, h*y2), stroke, StrokeCap.Round)
        when (kind) {
            "home" -> { line(.15f,.48f,.5f,.16f); line(.5f,.16f,.85f,.48f); line(.25f,.44f,.25f,.85f); line(.25f,.85f,.75f,.85f); line(.75f,.85f,.75f,.44f) }
            "preview" -> { line(.25f,.13f,.75f,.13f); line(.75f,.13f,.75f,.87f); line(.75f,.87f,.25f,.87f); line(.25f,.87f,.25f,.13f); line(.36f,.4f,.64f,.4f); line(.36f,.58f,.64f,.58f) }
            else -> { line(.18f,.26f,.82f,.26f); line(.18f,.5f,.82f,.5f); line(.18f,.74f,.65f,.74f) }
        }
    }
}

@Composable
private fun HistoryScreen(records: List<ScanRecord>, vm: ScanViewModel, action: (Uri, String, Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (records.isEmpty()) item { EmptyHistory() }
        items(records, key = { it.id }) { HistoryCard(it, vm, action) }
    }
}
@Composable
private fun EmptyHistory() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your scans will appear here", style = MaterialTheme.typography.titleMedium)
            Text("Saved documents will appear here.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable
private fun HistoryCard(record: ScanRecord, vm: ScanViewModel, action: (Uri, String, Boolean) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HistoryThumbnail(record)
                Text(record.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Text("${record.format} · ${displaySize(record.sizeBytes)} · ${record.pageCount} page(s)", style = MaterialTheme.typography.bodySmall)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.createdAt)), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { vm.fileAction(record, false, action) }) { Text("Open") }
                TextButton(onClick = { vm.fileAction(record, true, action) }) { Text("Share") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete saved scan?") },
        text = { Text("Delete ${record.displayName} from your phone, or remove only its history entry if the file is missing or inaccessible.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(record) }) { Text("Delete file") } },
        dismissButton = { Column {
            TextButton(onClick = { confirmDelete = false; vm.delete(record, true) }) { Text("Remove history only") }
            TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
        } })
}

@Composable
private fun HistoryThumbnail(record: ScanRecord) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, record.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(record.uri)
                if (record.format == "PDF") context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        renderer.openPage(0).use { page ->
                            val scale = 160.0 / maxOf(page.width, page.height)
                            Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(AndroidColor.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                } else ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1.0, 160.0 / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                }
            } catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { null }
        }
    }
    Box(Modifier.size(48.dp, 60.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "Saved document thumbnail", Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit) }
            ?: Text(record.format, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FilePreview(file: File, rotation: Int, pdfPage: Int?, modifier: Modifier) {
    var retry by remember(file.absolutePath, rotation, pdfPage) { mutableIntStateOf(0) }
    var loading by remember(file.absolutePath, rotation, pdfPage, retry) { mutableStateOf(true) }
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath, rotation, pdfPage, retry) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                if (pdfPage == null) ImageProcessor.decode(file, rotation, edge = 1400)
                else ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        pdf.openPage(pdfPage).use { page ->
                            val scale = 1200.0 / maxOf(page.width, page.height)
                            val image = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                            image.eraseColor(AndroidColor.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image
                        }
                    }
                }
            } catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { null }
        }
        loading = false
    }
    LaunchedEffect(bitmap, retry) { if (bitmap != null) loading = false }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "Document preview", Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) }
            ?: if (loading) Text("Loading preview…", style = MaterialTheme.typography.bodySmall) else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Could not load this preview", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { loading = true; retry++ }) { Text("Try again") }
            }
    }
}
