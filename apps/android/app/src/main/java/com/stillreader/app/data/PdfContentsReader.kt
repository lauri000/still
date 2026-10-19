package com.stillreader.app.data

import android.content.Context
import com.stillreader.app.core.PdfContentsEntry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** PDFium's bookmark binding maps remote GoTo actions to local page indices.
 * Read the original outline with action types intact; never execute PDF actions. */
internal class PdfContentsReader(private val context: Context) {
    fun read(file: File, checkCancelled: () -> Unit): List<PdfContentsEntry> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        PDDocument.load(file, memory).use { document ->
            val entries = mutableListOf<PdfContentsEntry>()
            val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
            val pages = document.pages
            val pageIndices = pages.mapIndexed { index, page -> page.cosObject to index }.toMap()
            fun collect(parent: PDOutlineNode, ancestors: List<String>) {
                if (ancestors.size >= 64) return
                var child = parent.firstChild
                var index = 0
                while (child != null && entries.size < 10_000) {
                    checkCancelled()
                    val item = child
                    if (!seen.add(item.cosObject)) return
                    val id = (ancestors.lastOrNull()?.let { "$it." } ?: "") + index++
                    // A malformed destination must not discard its siblings or children.
                    val page = try {
                        val destination = item.destination ?: (item.action as? PDActionGoTo)?.destination
                        val resolved = if (destination is PDNamedDestination) document.documentCatalog.findNamedDestinationPage(destination) else destination
                        (resolved as? PDPageDestination)?.let { dest ->
                            dest.page?.let { pageIndices[it.cosObject] }
                                ?: dest.pageNumber.takeIf { it in 0 until document.numberOfPages }
                        }
                    } catch (_: java.io.IOException) { null } catch (_: IllegalArgumentException) { null }
                    entries += PdfContentsEntry(id, item.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled section",
                        page, ancestors, item.firstChild != null)
                    collect(item, ancestors + id)
                    child = item.nextSibling
                }
            }
            document.documentCatalog.documentOutline?.let { collect(it, emptyList()) }
            return entries
        }
    }
}
