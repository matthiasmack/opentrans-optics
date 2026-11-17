package opentrans.model

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.CDATA
import javax.xml.stream.XMLStreamConstants.CHARACTERS
import javax.xml.stream.XMLStreamConstants.END_ELEMENT
import javax.xml.stream.XMLStreamConstants.SPACE
import javax.xml.stream.XMLStreamConstants.START_ELEMENT
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/*
 * Free XML content: the value of every xsd:anyType element (HEADER_UDX,
 * ITEM_UDX, REPORT_UDX, XML_SIGNATURE). An immutable, namespace-aware tree,
 * so UDX content can be inspected and changed without string handling, and
 * typed UDX (see opentrans.udx) can be read from and written into it.
 *
 * Equality is semantic: element and attribute names compare by namespace and
 * local name, not by prefix. Comments, processing instructions and
 * whitespace-only text between elements are not kept.
 */

/** A node of [XmlContent]: an element or a piece of text. */
sealed interface XmlNode

/** Text content. */
data class XmlText(val text: String) : XmlNode

/**
 * An element. [name] carries the namespace; its prefix is only a hint for
 * writing. Child elements are usually looked up by local name in the
 * element's own namespace: `element.child("PROJECT")`.
 */
data class XmlElement(
    val name: QName,
    val attributes: Map<QName, String> = emptyMap(),
    val children: List<XmlNode> = emptyList(),
) : XmlNode {
    init {
        require(name.localPart.isNotEmpty()) { "An XML element needs a name" }
    }

    /** Element in a namespace; `prefix` is only a hint for writing. */
    constructor(localName: String, namespace: String = "", prefix: String = "", attributes: Map<QName, String> = emptyMap(), children: List<XmlNode> = emptyList()) :
        this(QName(namespace, localName, prefix), attributes, children)

    val elements: List<XmlElement> get() = children.filterIsInstance<XmlElement>()

    /** Text directly inside this element (not that of child elements). */
    val text: String get() = children.filterIsInstance<XmlText>().joinToString("") { it.text }

    /** First child element with [localName] in this element's namespace. */
    fun child(localName: String): XmlElement? = elements.firstOrNull { it.name == sibling(localName) }

    /** All child elements with [localName] in this element's namespace. */
    fun children(localName: String): List<XmlElement> = elements.filter { it.name == sibling(localName) }

    /** Attribute without namespace, e.g. `level` in `<PRIORITY level="high"/>`. */
    fun attribute(localName: String): String? = attributes[QName(localName)]

    /** A name in this element's namespace, with its prefix. */
    fun sibling(localName: String) = QName(name.namespaceURI, localName, name.prefix)

    override fun toString() = XmlSerializer(emptyMap()).apply { element(this@XmlElement) }.result()
}

/** The content of an xsd:anyType element: elements and text, in document order. */
data class XmlContent(val nodes: List<XmlNode>) {
    constructor(vararg nodes: XmlNode) : this(nodes.toList())

    val elements: List<XmlElement> get() = nodes.filterIsInstance<XmlElement>()

    /** Text at the top level (not that of elements). */
    val text: String get() = nodes.filterIsInstance<XmlText>().joinToString("") { it.text }

    fun isEmpty() = nodes.isEmpty()

    /** Serializes the content on its own; every namespace it uses is declared in it. */
    fun toXml(): String = toXml(emptyMap())

    /**
     * Serializes the content for a place where the prefixes in [inScope]
     * (prefix -> namespace, "" for the default namespace) are already declared.
     */
    fun toXml(inScope: Map<String, String>): String = XmlSerializer(inScope).apply { nodes(nodes) }.result()

    override fun toString() = toXml()

    companion object {
        val EMPTY = XmlContent(emptyList())

        fun text(text: String) = XmlContent(listOf(XmlText(text)))

        /**
         * Parses XML text: any number of elements and text, e.g.
         * `<UDX.ACME.PROJECT xmlns="urn:acme">...</UDX.ACME.PROJECT>`.
         * Every prefix used must be declared in [xml] itself. DTDs and
         * external entities are refused.
         *
         * @throws IllegalArgumentException if [xml] is not well-formed
         */
        fun parse(xml: String): XmlContent {
            val reader = factory.createXMLStreamReader(StringReader("<content>$xml</content>"))
            try {
                reader.nextTag()
                val content = read(reader)
                while (reader.hasNext()) reader.next() // anything after the wrapper is malformed and throws here
                return content
            } catch (e: XMLStreamException) {
                throw IllegalArgumentException("Not well-formed XML content: ${e.message}", e)
            } finally {
                reader.close()
            }
        }

        /**
         * Reads the content of the element [reader] is positioned on (START_ELEMENT)
         * and leaves the reader on its END_ELEMENT. Used by opentrans-xml.
         */
        fun read(reader: XMLStreamReader): XmlContent {
            class Open(val name: QName, val attributes: Map<QName, String>, val children: MutableList<XmlNode> = mutableListOf())

            val top = mutableListOf<XmlNode>()
            val open = ArrayDeque<Open>()
            while (true) {
                when (reader.next()) {
                    START_ELEMENT -> {
                        val attributes = LinkedHashMap<QName, String>()
                        for (i in 0 until reader.attributeCount) attributes[reader.getAttributeName(i)] = reader.getAttributeValue(i)
                        open.addLast(Open(reader.name, attributes))
                    }
                    END_ELEMENT -> {
                        val closed = open.removeLastOrNull() ?: return XmlContent(top.map { if (it is XmlText) XmlText(it.text.trim()) else it })
                        (open.lastOrNull()?.children ?: top) += XmlElement(closed.name, closed.attributes, closed.children)
                    }
                    CHARACTERS, CDATA, SPACE -> {
                        val text = reader.text
                        if (text.isNotBlank()) {
                            val target = open.lastOrNull()?.children ?: top
                            val previous = target.lastOrNull()
                            if (previous is XmlText) target[target.lastIndex] = XmlText(previous.text + text) else target += XmlText(text)
                        }
                    }
                    else -> Unit // comments, processing instructions
                }
            }
        }

        private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
    }
}

/** Writes nodes compactly (no indentation, so mixed content stays as it is) and declares namespaces where needed. */
private class XmlSerializer(inScope: Map<String, String>) {
    private val out = StringBuilder()
    private val root: Map<String, String> = inScope + ("xml" to XMLConstants.XML_NS_URI)

    fun result() = out.toString()

    fun nodes(nodes: List<XmlNode>) = nodes.forEach { node(it, root) }

    fun element(e: XmlElement) = node(e, root)

    private fun node(node: XmlNode, scope: Map<String, String>) {
        when (node) {
            is XmlText -> out.append(escapeXml(node.text, attribute = false))
            is XmlElement -> element(node, scope)
        }
    }

    private fun element(e: XmlElement, outer: Map<String, String>) {
        var scope = outer
        val declarations = LinkedHashMap<String, String>()
        fun bind(prefix: String, namespace: String) {
            if ((scope[prefix] ?: "") != namespace) {
                declarations[prefix] = namespace
                scope = scope + (prefix to namespace)
            }
        }

        val namespace = e.name.namespaceURI
        val prefix = if (namespace.isEmpty()) "" else e.name.prefix
        bind(prefix, namespace)
        val tag = qualified(prefix, e.name.localPart)

        val attributes = e.attributes.map { (name, value) ->
            val ns = name.namespaceURI
            val attributePrefix = when {
                ns.isEmpty() -> ""
                ns == XMLConstants.XML_NS_URI -> "xml"
                name.prefix.isNotEmpty() && (scope[name.prefix] == ns || name.prefix !in declarations) -> name.prefix
                else -> scope.entries.firstOrNull { it.key.isNotEmpty() && it.value == ns }?.key
                    ?: generateSequence(1) { it + 1 }.map { "ns$it" }.first { it !in scope }
            }
            if (attributePrefix.isNotEmpty()) bind(attributePrefix, ns)
            qualified(attributePrefix, name.localPart) to value
        }

        out.append('<').append(tag)
        for ((p, ns) in declarations) {
            out.append(if (p.isEmpty()) " xmlns" else " xmlns:$p").append("=\"").append(escapeXml(ns, attribute = true)).append('"')
        }
        for ((name, value) in attributes) out.append(' ').append(name).append("=\"").append(escapeXml(value, attribute = true)).append('"')
        if (e.children.isEmpty()) {
            out.append("/>")
        } else {
            out.append('>')
            e.children.forEach { node(it, scope) }
            out.append("</").append(tag).append('>')
        }
    }

    private fun qualified(prefix: String, local: String) = if (prefix.isEmpty()) local else "$prefix:$local"
}

internal fun escapeXml(text: String, attribute: Boolean): String = buildString(text.length) {
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
