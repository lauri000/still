package com.stillreader.app.catalog

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

class CatalogXmlNode(val name: String, val attributes: Map<String, String>) {
    val children = mutableListOf<CatalogXmlNode>()
    val text = StringBuilder()
    fun descendants(name: String): List<CatalogXmlNode> = children.flatMap { (if (it.name == name) listOf(it) else emptyList()) + it.descendants(name) }
    fun first(name: String) = descendants(name).firstOrNull()
}
object CatalogXml {
    fun parse(data: ByteArray): CatalogXmlNode {
        require(data.size <= 8 * 1024 * 1024 && !data.decodeToString().contains("<!ENTITY")) { "This feed contains unsupported XML." }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val stack = mutableListOf<CatalogXmlNode>(); var root: CatalogXmlNode? = null
        val blocks = setOf("p", "div", "section", "br", "li", "h1", "h2", "h3", "tr")
        val parser = factory.newSAXParser().xmlReader
        parser.entityResolver = org.xml.sax.EntityResolver { _, _ -> org.xml.sax.InputSource("".reader()) }
        parser.contentHandler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes) {
                require(stack.size < 128) { "This document is too deeply nested." }
                val node = CatalogXmlNode(localName, (0 until attributes.length).associate { attributes.getQName(it) to attributes.getValue(it) })
                if (stack.isEmpty()) root = node else stack.last().children += node
                if (localName in blocks) stack.forEach { it.text.append("\n\n") }
                stack += node
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (stack.none { it.name in setOf("script", "style", "head") }) stack.forEach { it.text.append(ch, start, length) }
            }
            override fun endElement(uri: String?, localName: String, qName: String?) {
                stack.removeAt(stack.lastIndex)
                if (localName in blocks) stack.forEach { it.text.append("\n\n") }
            }
        }
        parser.parse(org.xml.sax.InputSource(data.inputStream()))
        return requireNotNull(root) { "This document has no content." }
    }
}
