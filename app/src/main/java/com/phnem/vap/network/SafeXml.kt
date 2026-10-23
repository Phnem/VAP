package com.phnem.vap.network

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal object SafeXml {
    fun parse(xml: String): Document {
        require(xml.length <= 2 * 1024 * 1024 && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities forbidden") }
        }.parse(InputSource(StringReader(xml.trim().trimStart('\uFEFF'))))
    }
    fun children(parent: Element, name: String): List<Element> = (0 until parent.childNodes.length).mapNotNull {
        (parent.childNodes.item(it) as? Element)?.takeIf { element -> element.localName == name || element.tagName == name }
    }
    fun text(parent: Element, name: String) = children(parent, name).firstOrNull()?.textContent?.trim()
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
