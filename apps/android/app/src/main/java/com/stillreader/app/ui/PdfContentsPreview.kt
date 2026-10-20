package com.stillreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stillreader.app.core.*
import com.stillreader.app.data.BookImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfContentsPreview(book: Book, importer: BookImporter, file: File, page: Int, modifier: Modifier = Modifier, startOffset: Int = 0, chooseOffset: (Int?) -> Unit = {}, choosePage: (Int) -> Unit) {
    var entries by remember(file) { mutableStateOf<List<PdfContentsEntry>>(emptyList()) }
    var loading by remember(file) { mutableStateOf(true) }
    var failed by remember(file) { mutableStateOf(false) }
    var panelOpen by rememberSaveable(file.path) { mutableStateOf<Boolean?>(null) }
    var sheetOpen by rememberSaveable(file.path) { mutableStateOf(false) }
    var collapsed by remember(file) { mutableStateOf(emptySet<String>()) }
    var selectedID by rememberSaveable(file.path) { mutableStateOf<String?>(null) }
    val current = PdfContents.current(entries, page, selectedID, if (book.isEpub) startOffset else null)
    LaunchedEffect(file) {
        try { entries = importer.contents(file, book) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    LaunchedEffect(page, current?.id) {
        if (entries.none { it.id == selectedID && it.pageIndex == page }) selectedID = null
        collapsed = collapsed - current?.ancestors.orEmpty().toSet()
    }
    val select: (PdfContentsEntry) -> Unit = { entry ->
        entry.pageIndex?.takeIf { it in book.pages.indices }?.let {
            selectedID = entry.id
            choosePage(it)
            chooseOffset(entry.utf16Offset)
            sheetOpen = false
        }
    }
    val toggle: (String) -> Unit = { id -> collapsed = if (id in collapsed) collapsed - id else collapsed + id }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 760.dp
        val visible = wide && (panelOpen ?: entries.isNotEmpty())
        Column {
            TextButton(onClick = { if (wide) panelOpen = !visible else sheetOpen = true }, modifier = Modifier.testTag("pdfContents")) {
                Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Contents")
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (visible) ContentsList(book, entries, loading, failed, current?.id, collapsed, toggle, select,
                    Modifier.width(280.dp).fillMaxHeight().testTag("contentsSidebar"))
                BookPreview(book, importer, file, page, Modifier.weight(1f).fillMaxHeight(), startOffset)
            }
        }
    }
    if (sheetOpen) ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Paper) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Contents", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { sheetOpen = false }, modifier = Modifier.testTag("closeContents")) { Text("Done") }
        }
        ContentsList(book, entries, loading, failed, current?.id, collapsed, toggle, select,
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
internal fun ContentsList(book: Book, entries: List<PdfContentsEntry>, loading: Boolean, failed: Boolean,
    selectedID: String?, collapsed: Set<String>, toggle: (String) -> Unit, select: (PdfContentsEntry) -> Unit, modifier: Modifier, readingOnly: Boolean = false) {
    val visible = PdfContents.visible(entries, collapsed)
    val listState = rememberLazyListState()
    LaunchedEffect(selectedID, visible.map { it.id }) {
        visible.indexOfFirst { it.id == selectedID }.takeIf { it >= 0 }?.let { index ->
            val shown = snapshotFlow { listState.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
            if (shown.none { it.index == index }) listState.scrollToItem((index - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(modifier.background(Surface, RoundedCornerShape(8.dp)), state = listState, contentPadding = PaddingValues(4.dp)) {
        if (loading || failed || entries.isEmpty()) item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when { loading -> "Loading contents…"; failed -> "Contents unavailable"; else -> if (book.isEpub) "No contents" else "No PDF contents" }, fontWeight = FontWeight.SemiBold)
                if (!loading) Text(if (failed) "You can still choose a ${book.unitName.lowercase()} using the controls."
                    else "This book has no contents. Use the ${book.unitName.lowercase()} controls to choose where to read.", color = Muted, fontSize = 14.sp)
            }
        }
        items(visible, key = { it.id }) { entry ->
            val selected = entry.id == selectedID
            val available = entry.pageIndex?.let { it in book.pages.indices && (!readingOnly || book.includesPage(it) && !book.pages[it].hasNoText && !(entry.utf16Offset != null && book.readingStart?.let { start -> start.pageID == book.pages[it].id && entry.utf16Offset < start.utf16Offset } == true)) } ?: false
            Row(Modifier.fillMaxWidth().background(if (selected) Forest.copy(alpha = .1f) else Surface, RoundedCornerShape(6.dp))
                .padding(start = (entry.level.coerceAtMost(6) * 12).dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry.hasChildren) IconButton(onClick = { toggle(entry.id) }, modifier = Modifier.size(40.dp).testTag("contentsToggle-${entry.id}")) {
                    Icon(if (entry.id in collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                        "${if (entry.id in collapsed) "Expand" else "Collapse"} ${entry.title}", Modifier.size(18.dp))
                } else Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f).selectable(selected, enabled = available, role = Role.Button, onClick = { select(entry) })
                    .testTag("contentsEntry-${entry.id}").heightIn(min = 48.dp).padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    Text(if (entry.pageIndex != null && !available) "Outside reading selection" else entry.pageIndex?.let { book.pages.getOrNull(it)?.let { p -> "${book.unitName} ${p.label}" } } ?: "No local destination", fontSize = 12.sp, color = Muted)
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderContentsSheet(session: ReadingSession, model: com.stillreader.app.LibraryViewModel, onClose: () -> Unit, onPage: () -> Unit) {
    var entries by remember(session) { mutableStateOf<List<PdfContentsEntry>>(emptyList()) }
    var loading by remember(session) { mutableStateOf(true) }
    var failed by remember(session) { mutableStateOf(false) }
    var collapsed by remember(session) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(session) {
        try { entries = model.importer.contents(model.repository.sourceFile(session.book), session.book) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
        val existing = entries.map { it.pageIndex to ReadingStructure.normalize(it.title) }.toSet()
        entries = entries + session.book.chapters.filter { (it.pageIndex to ReadingStructure.normalize(it.title)) !in existing }
            .map { PdfContentsEntry("chapter-${it.id}", it.title, it.pageIndex) }
        if (entries.isNotEmpty()) failed = false
        loading = false
    }
    val state by session.state.collectAsState()
    val current = session.structure.sectionAt(state.index)
    val selected = entries.firstOrNull { current != null && (if (session.book.isEpub) it.id == "chapter-${current.chapterID}" else it.pageIndex == current.pageIndex && ReadingStructure.normalize(it.title) == ReadingStructure.normalize(current.title)) }
        ?: PdfContents.current(entries, session.current?.pageIndex ?: 0)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Paper) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Contents", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done") }
        }
        TextButton(onClick = onPage, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Go to ${session.book.unitName.lowercase()}") }
        ContentsList(session.book, entries, loading, failed, selected?.id, collapsed,
            { collapsed = if (it in collapsed) collapsed - it else collapsed + it },
            { entry -> entry.pageIndex?.let { page ->
                if (entry.utf16Offset != null) session.seekPosition(ReadingPosition(session.book.pages[page].id, entry.utf16Offset))
                else session.structure.destination(entry.title, page)?.let(session::seek) ?: session.seekPage(page)
                onClose()
            } }, Modifier.fillMaxWidth().weight(1f).padding(16.dp), readingOnly = true)
    }
}
