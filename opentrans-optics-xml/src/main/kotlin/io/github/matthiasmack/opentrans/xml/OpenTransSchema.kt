package io.github.matthiasmack.opentrans.xml

import io.github.matthiasmack.opentrans.model.OpenTransValidationException
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory

/**
 * The bundled openTRANS 2.1 schema (plus BMEcat 2005, xmlmime, xmldsig).
 *
 * Hardening: neither the schema nor the validated documents may load anything
 * from outside. Schema imports are served from the classpath by [ClasspathResolver];
 * every other external access (DTDs, entities, remote schemas) is refused.
 */
object OpenTransSchema {
    private const val BASE = "/io/github/matthiasmack/opentrans/xsd/2.1/"
    private const val ROOT = "opentrans_2_1.xsd"

    val schema: Schema by lazy { load() }

    /** Validates [xml] and throws [OpenTransValidationException] listing all errors. */
    fun validate(xml: String) {
        rejectDoctype(xml)
        val errors = mutableListOf<String>()
        val validator = schema.newValidator().apply {
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            errorHandler = CollectingErrorHandler(errors)
        }
        try {
            validator.validate(StreamSource(StringReader(xml)))
        } catch (e: SAXParseException) {
            // Fatal errors (not well-formed, DOCTYPE, ...) end validation immediately.
            errors += e.describe()
        } catch (e: SAXException) {
            errors += e.message ?: e.toString()
        }
        if (errors.isNotEmpty()) throw OpenTransValidationException(errors)
    }

    /**
     * openTRANS documents never need a DOCTYPE. Refusing it up front blocks
     * XXE and entity expansion bombs before the validator expands anything.
     * (The validator's "disallow-doctype-decl" feature is accepted by the JDK
     * but has no effect on StreamSource input, so the check is done here.)
     */
    private fun rejectDoctype(xml: String) {
        val reader = prologFactory.createXMLStreamReader(StringReader(xml))
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD -> throw OpenTransValidationException(listOf("DOCTYPE declarations are not allowed"))
                    XMLStreamConstants.START_ELEMENT -> return
                }
            }
        } catch (e: XMLStreamException) {
            throw OpenTransValidationException(listOf("not well-formed: ${e.message}"))
        } finally {
            reader.close()
        }
    }

    private val prologFactory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    private fun load(): Schema {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            resourceResolver = ClasspathResolver
        }
        val root = resource(ROOT)
        return factory.newSchema(StreamSource(root, ROOT))
    }

    private fun resource(name: String): InputStream =
        OpenTransSchema::class.java.getResourceAsStream(BASE + name)
            ?: error("Bundled schema file missing: $BASE$name")

    /**
     * Serves the imported schemas from the classpath. The DTD referenced by
     * xmldsig-core-schema.xsd only declares entities the schema never uses,
     * so it is answered with an empty document instead of being downloaded.
     */
    private object ClasspathResolver : LSResourceResolver {
        private val bundled = setOf("bmecat_2005.xsd", "xmlmime.xsd", "xmldsig-core-schema.xsd")

        override fun resolveResource(
            type: String?, namespaceURI: String?, publicId: String?, systemId: String?, baseURI: String?,
        ): LSInput {
            val name = systemId?.substringAfterLast('/')
            return when {
                name in bundled -> {
                    Input(publicId, name, resource(name!!))
                }
                type == "http://www.w3.org/TR/REC-xml" -> {
                    Input(publicId, systemId, InputStream.nullInputStream())
                }
                else -> {
                    throw IllegalStateException("Refusing to load external resource: $systemId")
                }
            }
        }
    }

    private class Input(
        private var publicId: String?,
        private var systemId: String?,
        private var stream: InputStream?,
    ) : LSInput {
        override fun getByteStream() = stream
        override fun setByteStream(byteStream: InputStream?) { stream = byteStream }
        override fun getPublicId() = publicId
        override fun setPublicId(publicId: String?) { this.publicId = publicId }
        override fun getSystemId() = systemId
        override fun setSystemId(systemId: String?) { this.systemId = systemId }
        override fun getCharacterStream(): Reader? = null
        override fun setCharacterStream(characterStream: Reader?) = Unit
        override fun getStringData(): String? = null
        override fun setStringData(stringData: String?) = Unit
        override fun getBaseURI(): String? = null
        override fun setBaseURI(baseURI: String?) = Unit
        override fun getEncoding(): String? = null
        override fun setEncoding(encoding: String?) = Unit
        override fun getCertifiedText() = false
        override fun setCertifiedText(certifiedText: Boolean) = Unit
    }

    private class CollectingErrorHandler(private val errors: MutableList<String>) : ErrorHandler {
        override fun warning(e: SAXParseException) = Unit
        override fun error(e: SAXParseException) { errors += e.describe() }
        override fun fatalError(e: SAXParseException) = throw e
    }

    private fun SAXParseException.describe() = "line $lineNumber, column $columnNumber: $message"
}
