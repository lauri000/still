package com.stillreader.app.data

import com.stillreader.app.core.*
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the declared EPUB spine into text sections. Never extracts files or runs book HTML. */
object EpubImporter {
    private const val MAX_XML = 16 * 1024 * 1024
    private const val MAX_TEXT = 10_000_000
    private const val OPS = "http://www.idpf.org/2007/ops"
    private data class Item(val id: String, val path: String, val type: String, val properties: Set<String>)
    private data class Link(val title: String, val path: String, val fragment: String?, val level: Int)
    private data class Content(val text: String, val anchors: Map<String, Int>, val headings: List<TextHeading>)

    fun extract(file: File, filename: String, entities: Map<String, Int> = emptyMap(), checkpoint: () -> Unit = {}, progress: (ImportProgress) -> Unit = {}): Book {
        require(file.length() <= 250L * 1024 * 1024) { "This EPUB is larger than 250 MB." }
        fun xml(bytes: ByteArray) = xml(bytes, entities)
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().take(10_001).toList()
            require(entries.size <= 10_000 && entries.map { it.name }.toSet().size == entries.size) { "This EPUB has too many or duplicate archive entries." }
            require(entries.all { it.size in 0..512L * 1024 * 1024 } && entries.sumOf { it.size } <= 512L * 1024 * 1024) { "This EPUB expands beyond the supported size." }
            fun read(path: String): ByteArray {
                checkpoint()
                val entry = zip.getEntry(path) ?: error("The EPUB is missing a referenced file: $path")
                require(entry.size in 0..MAX_XML.toLong()) { "An EPUB text section is too large." }
                return zip.getInputStream(entry).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    val checksum = java.util.zip.CRC32()
                    while (true) {
                        checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(out.size() + count <= MAX_XML) { "An EPUB text section is too large." }
                        out.write(buffer, 0, count)
                        checksum.update(buffer, 0, count)
                    }
                    require(checksum.value == entry.crc) { "An EPUB section is damaged." }
                    out.toByteArray()
                }
            }
            require(read("mimetype").toString(Charsets.UTF_8).trim() == "application/epub+zip") { "This file is not an EPUB book." }
            val container = xml(read("META-INF/container.xml"))
            val packagePath = container.descendants("rootfile").firstOrNull()?.getAttribute("full-path")?.let { resolve("", it)?.first }
                ?: error("The EPUB has no package document.")
            val pkg = xml(read(packagePath))
            require(pkg.localName == "package" && pkg.getAttribute("version") in setOf("2.0", "3.0")) { "This EPUB version is not supported." }
            val metadata = pkg.child("metadata") ?: error("The EPUB has no metadata.")
            require(metadata.children().none { it.localName == "meta" && it.getAttribute("property") == "rendition:layout" && it.textContent.trim() == "pre-paginated" }) {
                "This is a fixed-layout EPUB. Still reads reflowable text books; illustrated page layouts are not supported yet."
            }
            val manifest = pkg.child("manifest")?.children().orEmpty().filter { it.localName == "item" }.map { e ->
                Item(e.getAttribute("id"), resolve(packagePath, e.getAttribute("href"))?.first ?: error("The EPUB refers to an unsupported external resource."),
                    e.getAttribute("media-type"), e.getAttribute("properties").split(' ').toSet())
            }
            require(manifest.map { it.id }.toSet().size == manifest.size) { "The EPUB has duplicate resource identifiers." }
            val items = manifest.associateBy { it.id }
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                val encryption = xml(read("META-INF/encryption.xml"))
                encryption.descendants("EncryptedData").forEach { data ->
                    val algorithm = data.descendants("EncryptionMethod").firstOrNull()?.getAttribute("Algorithm")
                    val path = data.descendants("CipherReference").firstOrNull()?.getAttribute("URI")?.let { resolve("", it)?.first }
                    val font = manifest.any { it.path == path && (it.type.startsWith("font/") || it.type in setOf("application/vnd.ms-opentype", "application/font-sfnt", "application/font-woff", "application/x-font-ttf")) }
                    require(font && algorithm in setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")) {
                        "This EPUB contains encrypted content. Import a DRM-free copy."
                    }
                }
            }
            val spine = pkg.child("spine") ?: error("The EPUB has no reading order.")
            val spineItems = spine.children().filter { it.localName == "itemref" && it.getAttribute("linear") != "no" }.map { ref ->
                require(!ref.getAttribute("properties").contains("rendition:layout-pre-paginated")) { "Fixed-layout EPUB sections are not supported yet." }
                items[ref.getAttribute("idref")] ?: error("The EPUB reading order references a missing resource.")
            }
            require(spineItems.isNotEmpty() && spineItems.map { it.path }.toSet().size == spineItems.size) { "The EPUB has no usable reading order or repeats a content file." }
            val links = mutableListOf<Link>()
            val nav = manifest.firstOrNull { "nav" in it.properties }
            if (nav != null) {
                val toc = xml(read(nav.path)).descendants("nav").firstOrNull { "toc" in it.semanticTypes() }
                fun collect(list: Element, level: Int) {
                    list.children().filter { it.localName == "li" }.forEach { li ->
                        val label = li.children().firstOrNull { it.localName in setOf("a", "span") }
                        if (label?.localName == "a") resolve(nav.path, label.getAttribute("href"))?.let { (path, fragment) ->
                            links += Link(label.textContent.trim().replace(Regex("\\s+"), " "), path, fragment, level)
                        }
                        li.children().filter { it.localName == "ol" }.forEach { collect(it, level + 1) }
                    }
                }
                toc?.children()?.firstOrNull { it.localName == "ol" }?.let { collect(it, 0) }
            }
            if (links.isEmpty()) {
                val ncx = items[spine.getAttribute("toc")] ?: manifest.firstOrNull { it.type == "application/x-dtbncx+xml" }
                if (ncx != null) {
                    fun collect(node: Element, level: Int) {
                        node.children().filter { it.localName == "navPoint" }.forEach { point ->
                            val label = point.child("navLabel")?.textContent?.trim().orEmpty()
                            point.child("content")?.getAttribute("src")?.let { resolve(ncx.path, it) }?.let { (path, fragment) -> links += Link(label, path, fragment, level) }
                            collect(point, level + 1)
                        }
                    }
                    xml(read(ncx.path)).descendants("navMap").firstOrNull()?.let { collect(it, 0) }
                }
            }
            val pages = mutableListOf<BookPage>()
            val contents = mutableMapOf<String, Pair<Int, Content>>()
            var total = 0
            for ((index, item) in spineItems.withIndex()) {
                checkpoint()
                require(item.type == "application/xhtml+xml") { "This EPUB uses an unsupported content format. Still currently reads XHTML text sections." }
                val content = text(xml(read(item.path)), checkpoint)
                if (content.text.isNotBlank()) {
                    total += content.text.length
                    require(total <= MAX_TEXT) { "This EPUB contains too much text to import." }
                    contents[item.path] = pages.size to content
                    pages += BookPage(number = pages.size + 1, text = content.text, extractedText = content.text,
                        isReviewed = true, sourceHref = item.path, headings = content.headings, edited = false)
                }
                progress(ImportProgress("Reading book…", .85f * (index + 1) / spineItems.size))
            }
            require(pages.isNotEmpty()) { "This EPUB has no readable text. Image-only books are not supported yet." }
            val chapters = links.mapNotNull { link ->
                val (page, content) = contents[link.path] ?: return@mapNotNull null
                val offset = if (link.fragment.isNullOrEmpty()) 0 else content.anchors[link.fragment] ?: return@mapNotNull null
                link.title.takeIf { it.isNotBlank() }?.let { BookChapter(title = it, pageIndex = page, level = link.level, source = "EPUB navigation", utf16Offset = offset, sourceAnchor = link.path + "#" + (link.fragment ?: "")) }
            }.ifEmpty {
                pages.flatMapIndexed { index, page -> page.headings.orEmpty().mapIndexed { ordinal, h -> BookChapter(title = page.text.substring(h.start, h.end), pageIndex = index, level = h.level, source = "EPUB heading", utf16Offset = h.start, sourceAnchor = "${page.sourceHref}#heading-$ordinal") } }
            }
            val coverID = metadata.children().firstOrNull { it.localName == "meta" && it.getAttribute("name") == "cover" }?.getAttribute("content")
            val coverItem = manifest.firstOrNull { "cover-image" in it.properties } ?: items[coverID]
            val cover = coverItem?.takeIf { it.type in setOf("image/jpeg", "image/png", "image/webp") && (zip.getEntry(it.path)?.size ?: Long.MAX_VALUE) <= 2 * 1024 * 1024 }
                ?.let { Base64.getEncoder().encodeToString(read(it.path)) }
            val identifierID = pkg.getAttribute("unique-identifier")
            val identifiers = metadata.children().filter { it.localName == "identifier" }
            val identifier = (identifiers.firstOrNull { it.getAttribute("id") == identifierID } ?: identifiers.firstOrNull())?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val book = Book(title = metadata.child("title")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: filename.substringBeforeLast('.'),
                author = metadata.children().filter { it.localName == "creator" }.joinToString(", ") { it.textContent.trim() },
                originalFilename = filename, pages = pages, chapters = chapters, sourceFormat = "epub",
                contentHash = sha256(file), epubIdentifier = identifier, coverData = cover,
                languageCodes = metadata.children().filter { it.localName == "language" }.map { it.textContent.trim() }.filter { it.isNotEmpty() })
            checkpoint()
            progress(ImportProgress("Preparing reader…", .95f))
            return ReaderPipeline.prepare(book)
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.child(name: String) = children().firstOrNull { it.localName == name }
    private fun Element.descendants(name: String): List<Element> = getElementsByTagNameNS("*", name).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    private fun Element.semanticTypes() = getAttributeNS(OPS, "type").split(Regex("\\s+"))

    private fun xml(bytes: ByteArray, entities: Map<String, Int>): Element {
        var source = when {
            bytes.size >= 2 && (bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() || bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) -> bytes.toString(Charsets.UTF_16)
            else -> bytes.toString(Charsets.UTF_8)
        }
        // EPUB 2 commonly declares an external XHTML DTD. Never load it or accept entity definitions.
        require(!source.contains("<!ENTITY") && !Regex("<!DOCTYPE[^>]*\\[").containsMatchIn(source)) { "The EPUB contains unsupported XML entity declarations." }
        source = source.removePrefix("\uFEFF").replace(Regex("<!DOCTYPE[^>]*>"), "").replace(Regex("<\\?xml[^?]*\\?>"), "")
        source = Regex("&([A-Za-z][A-Za-z0-9]+);").replace(source) { match ->
            entities[match.groupValues[1]]?.let { "&#$it;" } ?: match.value
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return try {
            val root = builder.parse(InputSource(StringReader(source))).documentElement
            val pending = ArrayDeque<Pair<Element, Int>>()
            pending.add(root to 1)
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeLast()
                require(depth <= 128) { "The EPUB markup is nested too deeply." }
                node.children().forEach { pending.add(it to depth + 1) }
            }
            root
        }
        catch (error: org.xml.sax.SAXException) { throw IllegalArgumentException("An EPUB section contains invalid XML.", error) }
    }

    /** URI resolution stays inside the ZIP; fragments and non-ASCII paths are decoded separately. */
    private fun resolve(base: String, href: String): Pair<String, String?>? {
        if (href.contains(Regex("^[A-Za-z][A-Za-z0-9+.-]*:")) || href.startsWith('/') || href.contains('\\')) return null
        fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        val path = decode(href.substringBefore('#').substringBefore('?'))
        val parts = mutableListOf<String>()
        val joined = if (path.isEmpty()) base else base.substringBeforeLast('/', "").let { if (it.isEmpty()) path else "$it/$path" }
        for (part in joined.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
            else -> { if (part.contains('\u0000') || part.contains('\\') || part.contains(':')) return null; parts += part }
        }
        return parts.joinToString("/") to href.takeIf { '#' in it }?.substringAfter('#')?.let(::decode)
    }

    private fun text(root: Element, checkpoint: () -> Unit): Content {
        val body = root.descendants("body").firstOrNull() ?: error("An EPUB section has no body.")
        val out = StringBuilder(); val anchors = mutableMapOf<String, Int>(); val headings = mutableListOf<TextHeading>()
        var breaks = 0; var space = false
        val blocks = setOf("p", "div", "section", "article", "header", "footer", "blockquote", "li", "dt", "dd", "tr", "pre", "h1", "h2", "h3", "h4", "h5", "h6")
        fun boundary(count: Int) { breaks = maxOf(breaks, count); space = false }
        fun append(value: String) {
            value.forEach { char ->
                if (char.isWhitespace() || Character.isSpaceChar(char)) { if (breaks == 0) space = true }
                else {
                    if (out.isNotEmpty()) { if (breaks > 0) repeat(breaks) { out.append('\n') } else if (space) out.append(' ') }
                    breaks = 0; space = false; out.append(char)
                }
            }
        }
        fun walk(node: Node, depth: Int) {
            require(depth <= 128) { "The EPUB markup is nested too deeply." }
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) { append(node.nodeValue.orEmpty()); return }
            val e = node as? Element ?: return
            checkpoint()
            val tag = e.localName
            val style = e.getAttribute("style").lowercase().replace(" ", "")
            if (tag in setOf("script", "style", "head", "nav", "svg", "rt", "rp", "img", "audio", "video") || e.hasAttribute("hidden") || e.getAttribute("aria-hidden") == "true" || style.contains("display:none") || style.contains("visibility:hidden")) return
            if ("pagebreak" in e.semanticTypes()) return
            if (tag == "br") { boundary(1); return }
            if (tag in blocks) boundary(2)
            val predicted = out.length + if (out.isEmpty()) 0 else if (breaks > 0) breaks else if (space) 1 else 0
            e.getAttribute("id").takeIf { it.isNotEmpty() }?.let { anchors.putIfAbsent(it, predicted) }
            val startBefore = out.length
            for (i in 0 until e.childNodes.length) walk(e.childNodes.item(i), depth + 1)
            if (tag in setOf("h1", "h2", "h3", "h4", "h5", "h6")) {
                var start = startBefore
                while (start < out.length && out[start].isWhitespace()) start++
                if (start < out.length) headings += TextHeading(start, out.length, tag.last().digitToInt() - 1)
            }
            if (tag in blocks) boundary(2) else if (tag in setOf("td", "th")) { space = true }
        }
        walk(body, 0)
        return Content(out.toString(), anchors.mapValues { it.value.coerceAtMost(out.length) }, headings)
    }
}
