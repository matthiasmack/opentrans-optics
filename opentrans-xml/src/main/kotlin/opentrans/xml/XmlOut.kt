package opentrans.xml

import opentrans.model.XmlContent

/**
 * Minimal indenting XML writer for openTRANS documents.
 *
 * openTRANS elements have either element-only or simple content (UDX aside),
 * so the writer can indent element-only content and keep simple content on
 * one line. openTRANS elements use the default namespace, BMEcat elements
 * the `bmecat:` prefix; both are declared once on the root element.
 */
internal class XmlOut {
    private val out = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    private var depth = 0

    /** Writes one document; the first element written becomes the root and declares the namespaces. */
    fun document(content: XmlOut.() -> Unit): String {
        namespacesPending = true
        content()
        return out.toString()
    }

    private var namespacesPending = false

    private fun rootNamespaces(): Array<Pair<String, String?>> =
        if (namespacesPending) arrayOf<Pair<String, String?>>("xmlns" to OT, "xmlns:bmecat" to BMECAT).also { namespacesPending = false }
        else emptyArray()

    /** Element without content. */
    fun empty(name: String, vararg attributes: Pair<String, String?>) {
        indent()
        out.append('<').append(name)
        attributes(rootNamespaces())
        attributes(attributes)
        out.append("/>\n")
    }

    /** Element with element-only content. */
    fun element(name: String, vararg attributes: Pair<String, String?>, content: XmlOut.() -> Unit) {
        indent()
        out.append('<').append(name)
        attributes(rootNamespaces())
        attributes(attributes)
        out.append(">\n")
        depth++
        content()
        depth--
        indent()
        out.append("</").append(name).append(">\n")
    }

    /** Element with simple content; skipped when [value] is null. */
    fun text(name: String, value: String?, vararg attributes: Pair<String, String?>) {
        if (value == null) return
        indent()
        out.append('<').append(name)
        attributes(attributes)
        out.append('>').append(escape(value, attribute = false)).append("</").append(name).append(">\n")
    }

    /** Element with xsd:anyType content, written compactly; namespaces the content needs are declared in it. */
    fun raw(name: String, value: XmlContent?) {
        if (value == null) return
        indent()
        out.append('<').append(name).append('>').append(value.toXml(ROOT_SCOPE)).append("</").append(name).append(">\n")
    }

    private companion object {
        /** Prefixes declared on the root element, see [rootNamespaces]. */
        val ROOT_SCOPE = mapOf("" to OT, "bmecat" to BMECAT)
    }

    private fun attributes(attributes: Array<out Pair<String, String?>>) {
        for ((key, value) in attributes) {
            if (value != null) out.append(' ').append(key).append("=\"").append(escape(value, attribute = true)).append('"')
        }
    }

    private fun indent() {
        repeat(depth) { out.append('\t') }
    }
}
