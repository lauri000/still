package com.stillreader.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stillreader.app.LibraryViewModel
import com.stillreader.app.core.Book
import com.stillreader.app.core.PdfTextRepair
import com.stillreader.app.data.PdfImporter
import kotlinx.coroutines.CancellationException
import java.io.File

@Composable
fun StillApp(model: LibraryViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var tool by rememberSaveable { mutableStateOf<String?>(null) }
    var toolPage by rememberSaveable { mutableIntStateOf(0) }
    var exportID by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importFile) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportID
        if (uri != null && id != null) model.export(uri, id)
        exportID = null
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.pause() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val book = state.books.firstOrNull { it.id == state.selectedID }
    BackHandler(enabled = state.importing != null || state.reader != null || tool != null || book != null) {
        when {
            state.importing != null -> model.cancelImport()
            state.reader != null -> model.closeReader()
            tool != null -> tool = null
            else -> model.select(null)
        }
    }
    Box(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.reader != null -> ReaderScreen(state.reader!!, model,
                onClose = model::closeReader,
                onReview = { page -> model.closeReader(); toolPage = page; tool = "Edit text" })
            book != null && tool != null -> BookTools(book, tool!!, toolPage, model, onClose = { tool = null })
            book != null -> BookScreen(book, model, canUndoImprovement = state.undoImprovementID == book.id, onBack = { model.select(null) },
                onTool = { name, page -> toolPage = page; tool = name },
                onExport = { exportID = book.id; exporter.launch("${book.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.stillbook.json") })
            else -> LibraryScreen(state.books, model::select) { picker.launch(arrayOf("application/pdf", "application/json", "application/octet-stream")) }
        }
        state.importing?.let { progress ->
            Dialog(onDismissRequest = model::cancelImport) {
                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Surface).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Preparing book", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Text(progress.message, color = Muted)
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = model::cancelImport, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
            }
        }
        state.error?.let { error ->
            AlertDialog(onDismissRequest = model::dismissError, title = { Text("Couldn’t complete that") },
                text = { Text(error) }, confirmButton = { TextButton(onClick = model::dismissError) { Text("OK") } })
        }
    }
}

@Composable
private fun LibraryScreen(books: List<Book>, select: (String) -> Unit, import: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Books", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Button(onClick = import, modifier = Modifier.testTag("importPDF"), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import PDF")
            }
        }
        if (books.size > 5) OutlinedTextField(search, { search = it }, label = { Text("Search books") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val filtered = books.filter { search.isBlank() || it.title.contains(search, true) || it.author.contains(search, true) }
        if (filtered.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Description, null, Modifier.size(40.dp), tint = Muted)
                Spacer(Modifier.height(18.dp))
                Text(if (books.isEmpty()) "No PDFs yet" else "No matching books", fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (books.isEmpty()) "Import a PDF to start reading." else "Try another title.", color = Muted)
            }
        } else LazyColumn {
            items(filtered, key = { it.id }) { book ->
                Row(Modifier.fillMaxWidth().clickable { select(book.id) }.testTag("book-${book.title}").padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Description, null, tint = Muted)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(book.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val page = book.pages.indexOfFirst { it.id == book.position?.pageID }
                        Text(when { book.position?.finished == true -> "Finished · ${book.pages.size} pages"
                            page >= 0 -> "Page ${page + 1} of ${book.pages.size}"
                            else -> "${book.pages.size} pages" }, color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Default.KeyboardArrowRight, null, tint = Muted)
                }
                HorizontalDivider(color = Line)
            }
        }
    }
}

@Composable
fun Header(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Default.ArrowBack, "Back") }
        Text(title, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun BookScreen(book: Book, model: LibraryViewModel, canUndoImprovement: Boolean, onBack: () -> Unit, onTool: (String, Int) -> Unit, onExport: () -> Unit) {
    var page by rememberSaveable(book.id) { mutableIntStateOf(book.pages.indexOfFirst { it.id == book.position?.pageID }.coerceAtLeast(book.readingStartPageIndex)) }
    var changed by rememberSaveable(book.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(book.readingStart) {
        page = book.pages.indexOfFirst { it.id == book.position?.pageID }.coerceAtLeast(book.readingStartPageIndex)
        changed = false
    }
    Column(Modifier.fillMaxSize()) {
        Header(book.title, onBack) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("bookOptions")) { Icon(Icons.Default.MoreHoriz, "Book options") }
                DropdownMenu(menu, { menu = false }) {
                    listOf("Edit text", "Chapters", "Choose starting word", "Book details").forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; onTool(name, page) })
                    }
                    DropdownMenuItem(text = { Text("Improve text") },
                        enabled = book.pages.any { PdfTextRepair.eligible(it) && it.isIncluded },
                        onClick = { menu = false; model.improveText(book.id) })
                    if (canUndoImprovement) {
                        DropdownMenuItem(text = { Text("Undo text improvement") },
                            onClick = { menu = false; model.undoTextImprovement(book.id) })
                    }
                    DropdownMenuItem(text = { Text("Export book backup") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete book", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = true })
                }
            }
        }
        Column(Modifier.weight(1f).widthIn(max = 1200.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PageSelector(page, book.pages.size, if (book.position != null && !changed) "Page" else "Start page") { page = it; changed = true }
            PdfContentsPreview(book, model.importer, model.repository.pdfFile(book.id), page, Modifier.weight(1f).fillMaxWidth()) { page = it; changed = true }
            if (book.pages[page].hasNoText) Text("No selectable text on this page. Choose another page or use Edit text.", fontSize = 12.sp, color = Muted)
            Button(onClick = { model.openReader(if (changed || book.position == null) page else null); changed = false },
                enabled = !(book.pages[page].hasNoText && (changed || book.position == null)),
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("startReading"), shape = RoundedCornerShape(12.dp)) {
                Text(if (book.position != null && !changed) { if (book.position.finished) "Read again" else "Continue reading" } else "Read")
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this book?") },
        text = { Text("This removes the imported PDF, corrections, and reading progress from Still on this device.") },
        confirmButton = { TextButton(onClick = { deleting = false; model.delete(book.id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@Composable
fun PageSelector(page: Int, count: Int, label: String = "Page", onChange: (Int) -> Unit) {
    var input by remember(page) { mutableStateOf((page + 1).toString()) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp)
        OutlinedTextField(input, { value ->
            if (value.length <= 6 && value.all(Char::isDigit)) {
                input = value
                value.toIntOrNull()?.takeIf { it in 1..count }?.let { onChange(it - 1) }
            }
        }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
            modifier = Modifier.width(76.dp).testTag("pageNumber"), isError = input.toIntOrNull() !in 1..count)
        Text("of $count", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Default.KeyboardArrowLeft, "Previous page") }
        IconButton(onClick = { onChange(page + 1) }, enabled = page + 1 < count) { Icon(Icons.AutoMirrored.Default.KeyboardArrowRight, "Next page") }
    }
}

@Composable
fun PdfPreview(importer: PdfImporter, file: File, page: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(8.dp)).background(Line.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, 1600)
        var bitmap by remember(file, page, width) { mutableStateOf<Bitmap?>(null) }
        var error by remember(file, page, width) { mutableStateOf<String?>(null) }
        LaunchedEffect(file, page, width) {
            try { bitmap = importer.preview(file, page, width) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "This page could not be previewed." }
        }
        when {
            bitmap != null -> Image(bitmap!!.asImageBitmap(), "PDF page ${page + 1} preview", Modifier.fillMaxSize().testTag("pdfPreview"), contentScale = ContentScale.Fit)
            error != null -> Text(error!!, Modifier.padding(24.dp), color = Muted)
            else -> CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}
