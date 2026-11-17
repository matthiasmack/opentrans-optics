package opentrans.xml

import opentrans.model.DateTime
import opentrans.model.RawXml
import java.io.StringReader
import java.math.BigDecimal
import javax.xml.XMLConstants
import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.CDATA
import javax.xml.stream.XMLStreamConstants.CHARACTERS
import javax.xml.stream.XMLStreamConstants.COMMENT
import javax.xml.stream.XMLStreamConstants.END_ELEMENT
import javax.xml.stream.XMLStreamConstants.PROCESSING_INSTRUCTION
import javax.xml.stream.XMLStreamConstants.SPACE
import javax.xml.stream.XMLStreamConstants.START_ELEMENT
import javax.xml.stream.XMLStreamReader

internal const val OT = "http://www.opentrans.org/XMLSchema/2.1"
internal const val BMECAT = "http://www.bmecat.org/bmecat/2005"


/** Thrown for valid openTRANS content that this version of the library does not model yet. */
class UnsupportedElementException(element: QName, parent: QName) :
    RuntimeException("Element ${element.localPart} inside ${parent.localPart} is not supported yet")

/**
 * Thin cursor over a StAX reader. Every read function expects the reader to
 * be positioned on a START_ELEMENT and leaves it on the matching END_ELEMENT.
 *
 * Documents are validated against the XSD before they reach this class, so
 * element order and cardinality are already guaranteed. The reader only has
 * to map elements to properties.
 */
internal class XmlIn(private val r: XMLStreamReader) {

    val name: QName get() = r.name

    fun attr(localName: String): String? = r.getAttributeValue(null, localName)

    fun requiredAttr(localName: String): String =
        attr(localName) ?: error("Attribute $localName missing on ${r.localName}")

    /** Calls [block] for each child element; [block] must consume the child completely. */
    fun children(block: (QName) -> Unit) {
        val parent = r.name
        while (true) {
            val event = r.nextTag()
            if (event == END_ELEMENT) return
            val child = r.name
            block(child)
            check(r.eventType == END_ELEMENT && r.name == child) { "Reader did not consume ${child.localPart} inside ${parent.localPart}" }
        }
    }

    /** For use in [children]: the child is valid openTRANS, but not modelled yet. */
    fun unsupported(child: QName, parent: QName): Nothing = throw UnsupportedElementException(child, parent)

    // --- simple content ---

    fun text(): String = r.elementText
    fun decimal(): BigDecimal = BigDecimal(text().trim())
    fun int(): Int = text().trim().toInt()
    fun dateTime(): DateTime = DateTime(text().trim())

    /** bmecat:dtBOOLEAN accepts any case ("TRUE", "false", ...). */
    fun boolean(): Boolean = text().trim().equals("true", ignoreCase = true)

    // --- xsd:anyType content ---

    /**
     * Captures the content of an xsd:anyType element (UDX) as XML text.
     *
     * Whitespace-only text between elements is dropped, so indentation does
     * not become part of the value. Namespace declarations that the content
     * relies on but that were declared further up in the document are added
     * to the captured elements, so the result is self-contained.
     */
    fun raw(): RawXml {
        val out = StringBuilder()
        val declared = ArrayDeque<Set<String>>()
        var startTagOpen = false
        var depth = 0

        fun closeStartTag() {
            if (startTagOpen) out.append('>')
            startTagOpen = false
        }

        while (true) {
            when (r.next()) {
                START_ELEMENT -> {
                    closeStartTag()
                    depth++
                    val inScope = declared.lastOrNull() ?: emptySet()
                    val here = mutableSetOf<String>()
                    out.append('<').append(qualified(r.prefix, r.localName))
                    for (i in 0 until r.namespaceCount) {
                        val prefix = r.getNamespacePrefix(i).orEmpty()
                        out.append(nsDecl(prefix, r.getNamespaceURI(i)))
                        here += prefix
                    }
                    val used = buildList {
                        add(r.prefix.orEmpty())
                        for (i in 0 until r.attributeCount) r.getAttributePrefix(i)?.takeIf { it.isNotEmpty() }?.let(::add)
                    }
                    for (prefix in used) {
                        if (prefix !in here && prefix !in inScope) {
                            val uri = r.getNamespaceURI(prefix.ifEmpty { XMLConstants.DEFAULT_NS_PREFIX }).orEmpty()
                            if (prefix.isNotEmpty() || uri.isNotEmpty()) out.append(nsDecl(prefix, uri))
                            here += prefix
                        }
                    }
                    for (i in 0 until r.attributeCount) {
                        out.append(' ').append(qualified(r.getAttributePrefix(i), r.getAttributeLocalName(i)))
                            .append("=\"").append(escape(r.getAttributeValue(i), attribute = true)).append('"')
                    }
                    declared.addLast(inScope + here)
                    startTagOpen = true
                }
                END_ELEMENT -> {
                    if (depth == 0) return RawXml(out.toString())
                    if (startTagOpen) out.append("/>") else out.append("</").append(qualified(r.prefix, r.localName)).append('>')
                    startTagOpen = false
                    declared.removeLast()
                    depth--
                }
                CHARACTERS, CDATA, SPACE -> {
                    val text = r.text
                    if (text.isNotBlank()) {
                        closeStartTag()
                        out.append(escape(if (depth == 0) text.trim() else text, attribute = false))
                    }
                }
                COMMENT, PROCESSING_INSTRUCTION -> Unit
            }
        }
    }

    private fun qualified(prefix: String?, local: String) = if (prefix.isNullOrEmpty()) local else "$prefix:$local"

    private fun nsDecl(prefix: String, uri: String) = if (prefix.isEmpty()) " xmlns=\"$uri\"" else " xmlns:$prefix=\"$uri\""

    companion object {
        /** StAX factory hardened against XXE: no DTD processing, no external entities. */
        private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }

        /** Positions a reader on the root element of [xml] and runs [block] on it. */
        fun <T> read(xml: String, block: XmlIn.() -> T): T {
            val reader = factory.createXMLStreamReader(StringReader(xml))
            try {
                reader.nextTag()
                return XmlIn(reader).block()
            } finally {
                reader.close()
            }
        }
    }
}

internal fun escape(text: String, attribute: Boolean): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> if (attribute) append("&quot;") else append(c)
            else -> append(c)
        }
    }
}
