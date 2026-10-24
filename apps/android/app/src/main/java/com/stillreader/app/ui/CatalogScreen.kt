package com.stillreader.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stillreader.app.LibraryViewModel
import com.stillreader.app.catalog.*
import kotlinx.coroutines.*
import java.util.Locale

@Composable
fun CatalogScreen(library: LibraryViewModel, back: () -> Unit, catalog: CatalogViewModel = viewModel(), networkAllowed: Boolean = rememberCatalogNetworkPermission()) {
    val context = LocalContext.current
    if (!networkAllowed) {
        BackHandler(onBack = back)
        Column(Modifier.fillMaxSize()) {
            Header("Discover", back)
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Network access is disabled for Still.", fontSize = 22.sp)
                Spacer(Modifier.height(16.dp))
                Text("Allow Network in Still’s app permissions to browse and download books. Saved books remain available offline.", color = Muted)
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open app settings") }
            }
        }
        return
    }
    val state by catalog.state.collectAsStateWithLifecycle()
    val books by library.state.collectAsStateWithLifecycle()
    var languages by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val selected = state.selected
    BackHandler { if (selected != null) catalog.select(null) else back() }
    LaunchedEffect(Unit) { if (state.page == null) catalog.load() }
    DisposableEffect(catalog) { onDispose { catalog.cancel() } }
    Column(Modifier.fillMaxSize()) {
        Header("Discover", { if (selected != null) catalog.select(null) else back() })
        if (selected != null) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                CatalogCover(selected.coverURL, catalog.provider.client, Modifier.size(140.dp, 200.dp))
                Text(selected.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(selected.authors.joinToString(", "), color = Muted)
                Text(selected.languages.joinToString(", ") { Locale.forLanguageTag(it).displayLanguage }, color = Muted)
                val existing = books.books.firstOrNull { it.catalogID == selected.id }
                Button(onClick = { if (existing != null) library.readBook(existing.id) else library.download(selected, catalog.provider) },
                    enabled = books.importing == null && (existing != null || selected.epubURL != null), modifier = Modifier.testTag("downloadEPUB")) {
                    Text(if (existing != null) "Read book" else "Download EPUB")
                }
                if (selected.summary.isNotEmpty()) Text(selected.summary)
                if (selected.subjects.isNotEmpty()) Text(selected.subjects.joinToString(" · "), color = Muted)
                selected.webURL?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("View at Project Gutenberg") } }
                Text("Saved on this device for offline reading.", color = Muted, fontSize = 12.sp)
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(catalog.provider.name, fontSize = 22.sp, modifier = Modifier.padding(top = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.query, catalog::query, label = { Text("Title or author") }, singleLine = true, modifier = Modifier.weight(1f).testTag("catalogSearch"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { catalog.load() }))
                TextButton(onClick = { catalog.load() }, enabled = !state.loading) { Text("Search") }
            }
            Box {
                TextButton(onClick = { languages = true }) { Text(if (state.language.isEmpty()) "All languages" else Locale.forLanguageTag(state.language).displayLanguage) }
                DropdownMenu(languages, { languages = false }) {
                    listOf("", "en", "fi", "sv", "fr", "de", "es", "it", "pt", "nl", "ru", "zh", "ja").forEach { code ->
                        DropdownMenuItem(text = { Text(if (code.isEmpty()) "All languages" else Locale.forLanguageTag(code).displayLanguage) }, onClick = { languages = false; catalog.language(code) })
                    }
                }
            }
            when {
                state.loading -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Text("Loading books…", Modifier.padding(top = 16.dp), color = Muted)
                    if (state.waiting) Text("The catalog is responding slowly. You can keep waiting or cancel.", color = Muted)
                    TextButton(onClick = catalog::stopLoading) { Text("Cancel") }
                }
                state.error != null -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(state.error!!); TextButton(onClick = catalog::retry) { Text("Try again") }
                }
                state.page != null -> {
                    val page = state.page!!
                    LazyColumn(Modifier.weight(1f)) {
                        if (page.books.isEmpty()) item { Text("No books on this page match your search. Try another search or the next page.", Modifier.padding(vertical = 24.dp), color = Muted) }
                        items(page.books, key = { it.id }) { book ->
                            Row(Modifier.fillMaxWidth().clickable { catalog.select(book) }.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                CatalogCover(book.coverURL, catalog.provider.client, Modifier.size(54.dp, 78.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(book.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                    Text(book.authors.joinToString(", "), color = Muted, fontSize = 14.sp)
                                    if (books.books.any { it.catalogID == book.id }) Text("In your library", fontSize = 12.sp)
                                }
                            }
                            HorizontalDivider(color = Line)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { catalog.load(page.previous) }, enabled = page.previous != null) { Text("Previous") }
                        page.total?.let { Text("$it catalog entries", fontSize = 12.sp, color = Muted) }
                        TextButton(onClick = { catalog.load(page.next) }, enabled = page.next != null) { Text("Next") }
                    }
                }
            }
            Text("Public-domain status varies by country. Check the provider’s terms for your location.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}
@Composable
private fun rememberCatalogNetworkPermission(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun allowed() = context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
    var granted by remember(context) { mutableStateOf(allowed()) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = allowed() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return granted
}

@Composable
fun CatalogCover(url: String?, client: CatalogClient, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url, client) {
        if (url != null) try {
            val data = client.get(url, 2 * 1024 * 1024)
            bitmap = withContext(Dispatchers.Default) { decodeCover(data) }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }
    Box(modifier.background(Line), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text("▤", color = Muted, fontSize = 24.sp)
    }
}
fun decodeCover(data: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 600).coerceAtLeast(1) }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
}
