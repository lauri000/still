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
import com.stillreader.app.data.PdfImporter
import com.stillreader.app.data.BookImporter
import kotlinx.coroutines.CancellationException
import java.io.File

@Composable
fun StillApp(model: LibraryViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var discovering by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.selectedID) { if (state.selectedID != null) discovering = false }
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
    BackHandler(enabled = state.importing != null || state.opening || state.reader != null || tool != null) {
        when {
            state.importing != null -> model.cancelImport()
            state.reader != null || state.opening -> model.select(null)
            tool != null -> { tool = null; model.select(null) }
            else -> model.select(null)
        }
    }
    // Let the ribbon reach the window edges; controls and passage inset themselves.
    val safeInsets = if (state.reader != null) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
        else WindowInsets.safeDrawing
    Box(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(safeInsets)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.reader != null -> ReaderScreen(state.reader!!, model,
                onClose = { model.select(null) },
                onReview = { page -> model.closeReader(); toolPage = page; tool = "Edit text" })
            book != null && tool != null -> BookTools(book, tool!!, toolPage, model, onClose = { tool = null; if (model.state.value.reader == null && !model.state.value.opening) model.select(null) })
            discovering -> CatalogScreen(model, back = { discovering = false })
            else -> LibraryScreen(state.books, model::readBook, discover = { discovering = true },
                options = { item -> BookOptions(item, model, onTool = { name ->
                    model.select(item.id)
                    toolPage = item.pages.indexOfFirst { it.id == item.position?.pageID }.coerceAtLeast(item.readingStartPageIndex)
                    tool = name
                }, onExport = {
                    exportID = item.id
                    exporter.launch("${item.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")}.stillbook.json")
                }) }) { picker.launch(arrayOf("application/pdf", "application/epub+zip", "application/json", "application/octet-stream")) }
        }
        if (state.opening && state.importing == null) Dialog(onDismissRequest = { model.select(null) }) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Surface).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Opening book…", fontSize = 20.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { model.select(null) }, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
        state.preparationID?.let { id ->
            AlertDialog(onDismissRequest = model::dismissPreparation, title = { Text("Prepare this book?") },
                text = { Text("This older book needs to be reprocessed before reading. Your corrections and page choices are kept.") },
                confirmButton = { TextButton(onClick = { model.reprocess(id, openAfter = true) }, modifier = Modifier.testTag("prepareBook")) { Text("Reprocess and read") } },
                dismissButton = { TextButton(onClick = model::dismissPreparation) { Text("Cancel") } })
        }
        state.importing?.let { progress ->
            Dialog(onDismissRequest = model::cancelImport) {
                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Surface).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Preparing book", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Text(progress.message, color = Muted)
                    if (progress.fraction < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
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
private fun LibraryScreen(books: List<Book>, select: (String) -> Unit, discover: () -> Unit, options: @Composable (Book) -> Unit, import: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Books", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = discover, modifier = Modifier.testTag("discoverBooks")) { Text("Discover") }
            Button(onClick = import, modifier = Modifier.testTag("importPDF"), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import file")
            }
        }
        if (books.size > 5) OutlinedTextField(search, { search = it }, label = { Text("Search books") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val filtered = books.filter { search.isBlank() || it.title.contains(search, true) || it.author.contains(search, true) }
        if (filtered.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Description, null, Modifier.size(40.dp), tint = Muted)
                Spacer(Modifier.height(18.dp))
                Text(if (books.isEmpty()) "No books yet" else "No matching books", fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (books.isEmpty()) "Import a PDF or EPUB, or discover a book to read." else "Try another title.", color = Muted)
            }
        } else LazyColumn {
            items(filtered, key = { it.id }) { book ->
                Row(Modifier.fillMaxWidth().clickable { select(book.id) }.testTag("book-${book.title}").padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cover = remember(book.coverData) { book.coverData?.let { runCatching { decodeCover(java.util.Base64.getDecoder().decode(it)) }.getOrNull() } }
                    if (cover != null) Image(cover.asImageBitmap(), null, Modifier.size(42.dp, 60.dp), contentScale = ContentScale.Fit)
                    else Icon(Icons.Outlined.Description, null, tint = Muted)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(book.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val page = book.pages.indexOfFirst { it.id == book.position?.pageID }
                        Text(when { book.position?.finished == true -> "Finished · ${book.pages.size} ${book.unitPlural}"
                            page >= 0 -> "${book.unitName} ${page + 1} of ${book.pages.size}"
                            else -> "${book.pages.size} ${book.unitPlural}" }, color = Muted, fontSize = 12.sp)
                    }
                    options(book)
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
private fun BookOptions(book: Book, model: LibraryViewModel, onTool: (String) -> Unit, onExport: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("bookOptions-${book.id}")) {
            Icon(Icons.Default.MoreHoriz, "Book options for ${book.title}")
        }
        DropdownMenu(menu, { menu = false }) {
            listOf("Edit text", "Chapters", "Choose starting word", "Book details").forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; onTool(name) })
            }
            DropdownMenuItem(text = { Text("Reprocess book") }, modifier = Modifier.testTag("reprocessBook"),
                onClick = { menu = false; model.reprocess(book.id) })
            if (state.undoReprocessID == book.id) DropdownMenuItem(text = { Text("Undo reprocessing") },
                onClick = { menu = false; model.undoReprocess(book.id) })
            DropdownMenuItem(text = { Text("Export book backup") }, onClick = { menu = false; onExport() })
            DropdownMenuItem(text = { Text("Delete book", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = true })
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this book?") },
        text = { Text("This removes the imported book, corrections, and reading progress from Still on this device.") },
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

@Composable
fun BookPreview(book: Book, importer: BookImporter, file: File, page: Int, modifier: Modifier = Modifier, startOffset: Int = 0) {
    if (!book.isEpub) PdfPreview(importer.pdf, file, page, modifier)
    else {
        val paragraphs = remember(book.pages[page].text, startOffset) { book.pages[page].text.drop(startOffset.coerceIn(0, book.pages[page].text.length)).take(6000).split("\n\n") }
        LazyColumn(modifier.clip(RoundedCornerShape(8.dp)).background(Surface).testTag("epubPreview"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(paragraphs.size) { index -> Text(paragraphs[index], fontSize = 18.sp, lineHeight = 28.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif) }
        }
    }
}
