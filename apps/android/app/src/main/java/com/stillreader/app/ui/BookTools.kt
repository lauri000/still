package com.stillreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stillreader.app.LibraryViewModel
import com.stillreader.app.core.*

@Composable
fun BookTools(book: Book, tool: String, initialPage: Int, model: LibraryViewModel, onClose: () -> Unit) {
    var page by rememberSaveable(book.id, tool) { mutableIntStateOf(initialPage.coerceIn(book.pages.indices)) }
    val close = { model.pause(); onClose() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Header(tool, close)
        when (tool) {
            "Edit text" -> {
                Column(Modifier.padding(20.dp)) { PageSelector(page, book.pages.size) { model.pause(); page = it } }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                    if (maxWidth > 700.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        PdfPreview(model.importer, model.repository.pdfFile(book.id), page, Modifier.weight(1f).fillMaxHeight())
                        EditPanel(book, page, model, Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        PdfPreview(model.importer, model.repository.pdfFile(book.id), page, Modifier.fillMaxWidth().height(240.dp))
                        EditPanel(book, page, model, Modifier.fillMaxWidth())
                    }
                }
            }
            "Choose starting word" -> StartingWord(book, page, { page = it }, model, close)
            "Chapters" -> Chapters(book, page, model, close)
            else -> Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                OutlinedTextField(book.title, { text -> model.updateBook(book.id, debounce = true) { it.copy(title = text) } },
                    label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(book.author, { text -> model.updateBook(book.id, debounce = true) { it.copy(author = text) } },
                    label = { Text("Author") }, modifier = Modifier.fillMaxWidth())
                Text("${book.pages.size} pages · ${book.originalFilename}", color = Muted)
                Text("The original PDF is kept with your corrections and reading progress on this device. Export a book backup to move it to another device.", color = Muted, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun EditPanel(book: Book, pageIndex: Int, model: LibraryViewModel, modifier: Modifier) {
    val page = book.pages[pageIndex]
    Column(modifier.padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page.hasNoText) Text("No selectable text was found. You can enter text here or exclude this page.", color = Muted, fontSize = 13.sp)
        if ('\uFFFD' in page.text) Text("Some characters could not be decoded. Compare them with the PDF.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        if (Regex("[\\p{L}]-[\\t ]*\\n[\\p{L}]").containsMatchIn(page.text)) Text("Some words may be split across lines. Check the hyphens against the PDF.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(page.text, { text -> model.updateBook(book.id, debounce = true) { it.editText(pageIndex, text) } },
            label = { Text("Page text") }, modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp).testTag("pageText"),
            textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 26.sp))
        OptionToggle("Include this page", page.isIncluded) { included ->
            model.updateBook(book.id) { it.copy(pages = it.pages.mapIndexed { index, item -> if (index == pageIndex) item.copy(isIncluded = included) else item }) }
        }
        OptionToggle("Reviewed", page.isReviewed) { reviewed ->
            model.updateBook(book.id) { it.copy(pages = it.pages.mapIndexed { index, item -> if (index == pageIndex) item.copy(isReviewed = reviewed) else item }) }
        }
        TextButton(onClick = { model.restoreExtractedText(book.id, pageIndex) }) { Text("Restore extracted text") }
    }
}

@Composable
private fun StartingWord(book: Book, page: Int, changePage: (Int) -> Unit, model: LibraryViewModel, close: () -> Unit) {
    val words = remember(book.pages[page].text) { book.pages[page].words }
    var offset by rememberSaveable(page) { mutableIntStateOf(book.readingStart?.takeIf { it.pageID == book.pages[page].id }?.utf16Offset ?: 0) }
    val selected = words.firstOrNull { it.offset + it.length > offset }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Tap the first word to keep.", color = Muted)
        PageSelector(page, book.pages.size, onChange = changePage)
        val annotated = remember(book.pages[page].text, selected) {
            buildAnnotatedString {
                append(book.pages[page].text)
                selected?.let {
                    if (it.offset > 0) addStyle(SpanStyle(color = Muted.copy(alpha = .5f)), 0, it.offset)
                    addStyle(SpanStyle(background = Forest.copy(alpha = .18f), fontWeight = FontWeight.Bold), it.offset, it.offset + it.length)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).background(Surface).padding(20.dp)) {
            if (words.isEmpty()) Text("No selectable text on this page.", color = Muted)
            BasicText(annotated, style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 32.sp, color = Ink),
                onTextLayout = { layout = it }, modifier = Modifier.testTag("startingWords").pointerInput(annotated) {
                    detectTapGestures { point ->
                        val position = layout?.getOffsetForPosition(point) ?: return@detectTapGestures
                        offset = words.lastOrNull { it.offset <= position }?.offset ?: 0
                    }
                })
        }
        if (selected != null) Text("Start at “${selected.text}”", fontSize = 14.sp, color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { model.updateBook(book.id) { it.withReadingStart(null) }; close() }) { Text("Restore beginning") }
            Button(enabled = selected != null, onClick = {
                model.updateBook(book.id) { it.withReadingStart(ReadingPosition(book.pages[page].id, selected!!.offset)) }; close()
            }) { Text("Start here") }
        }
    }
}

@Composable
private fun Chapters(book: Book, initialPage: Int, model: LibraryViewModel, close: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (book.chapters.isEmpty()) Text("This PDF has no chapter bookmarks. You can add your own.", color = Muted)
        book.chapters.sortedBy { it.pageIndex }.forEach { chapter ->
            Row(Modifier.fillMaxWidth().clickable {
                // Chapter navigation changes the reading position, preserving the chosen beginning.
                model.openReader(seekPage = chapter.pageIndex); close()
            }.padding(start = (chapter.level.coerceAtMost(6) * 12).dp)) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(chapter.title)
                    Text("Page ${book.pages[chapter.pageIndex].label}", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { model.updateBook(book.id) { it.copy(chapters = it.chapters.filterNot { item -> item.id == chapter.id }) } }) {
                    Icon(Icons.Outlined.Delete, "Remove ${chapter.title}", tint = Muted)
                }
            }
            HorizontalDivider(color = Line)
        }
        OutlinedButton(onClick = { adding = true }) { Text("Add chapter") }
    }
    if (adding) {
        var title by remember { mutableStateOf("") }
        var page by remember { mutableIntStateOf(initialPage) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add chapter") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Chapter title") }, singleLine = true)
                PageSelector(page, book.pages.size) { page = it }
            }
        }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
            model.updateBook(book.id) { it.copy(chapters = (it.chapters + BookChapter(title = title.trim(), pageIndex = page)).sortedBy { it.pageIndex }) }
            adding = false
        }) { Text("Add") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
}
