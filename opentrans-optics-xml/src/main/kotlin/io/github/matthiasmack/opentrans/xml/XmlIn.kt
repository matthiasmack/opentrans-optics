package io.github.matthiasmack.opentrans.xml

import io.github.matthiasmack.opentrans.model.DateTime
import io.github.matthiasmack.opentrans.model.XmlContent
import java.io.StringReader
import java.math.BigDecimal
import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.END_ELEMENT
import javax.xml.stream.XMLStreamReader

internal const val OT = "http://www.opentrans.org/XMLSchema/2.1"
internal const val BMECAT = "http://www.bmecat.org/bmecat/2005"

/**
 * Thin cursor over a StAX reader. Every read function expects the reader to
 * be positioned on a START_ELEMENT and leaves it on the matching END_ELEMENT.
 *
 * Documents are validated against the XSD before they reach this class, so
 * element order and cardinality are already guaranteed. The reader only has
 * to map elements to properties.
 */
internal class XmlIn(
    private val r: XMLStreamReader,
) {
    val name: QName get() = r.name

    fun attr(localName: String): String? = r.getAttributeValue(null, localName)

    fun requiredAttr(localName: String): String = attr(localName) ?: error("Attribute $localName missing on ${r.localName}")

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

    /**
     * For use in [children]: a child the generated reader does not expect. The
     * document was validated against the XSD before, so this means the
     * generator and the schema disagree, a bug in the library.
     */
    fun unsupported(
        child: QName,
        parent: QName,
    ): Nothing =
        throw IllegalStateException(
            "Valid element ${child.localPart} inside ${parent.localPart} is not mapped; this is a bug in the library",
        )

    // --- simple content ---

    fun text(): String = r.elementText

    fun decimal(): BigDecimal = BigDecimal(text().trim())

    fun int(): Int = text().trim().toInt()

    fun dateTime(): DateTime = DateTime(text().trim())

    /** bmecat:dtBOOLEAN accepts any case ("TRUE", "false", ...). */
    fun boolean(): Boolean = text().trim().equals("true", ignoreCase = true)

    // --- xsd:anyType content ---

    /** Reads the content of an xsd:anyType element (UDX) as an [XmlContent] tree. */
    fun raw(): XmlContent = XmlContent.read(r)

    companion object {
        /** StAX factory hardened against XXE: no DTD processing, no external entities. */
        private val factory: XMLInputFactory =
            XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
                setProperty(XMLInputFactory.IS_COALESCING, true)
            }

        /** Positions a reader on the root element of [xml] and runs [block] on it. */
        fun <T> read(
            xml: String,
            block: XmlIn.() -> T,
        ): T {
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

internal fun escape(
    text: String,
    attribute: Boolean,
): String =
    buildString(text.length) {
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
