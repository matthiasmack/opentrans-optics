package io.github.matthiasmack.opentrans.xml

import io.github.matthiasmack.opentrans.model.DocumentTypeException
import io.github.matthiasmack.opentrans.model.OpenTransDocument
import io.github.matthiasmack.opentrans.model.OpenTransValidationException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamException

/**
 * Entry point for reading and writing openTRANS 2.1 documents.
 *
 * Strict in both directions: input is validated against the XSD before it is
 * mapped, output is validated before it is returned. An invalid document
 * never enters or leaves the library.
 *
 * ```
 * when (val document = OpenTransXml.read(xml)) {
 *     is Order -> ...
 *     is Invoice -> ...
 *     else -> ...
 * }
 * val order: Order = OpenTransXml.readAs(xml)
 * val xml = OpenTransXml.write(order)
 * ```
 *
 * Input given as bytes (a file, a stream, a byte array) is decoded the way the
 * XML says: byte order mark first, then the `encoding` of the XML
 * declaration, UTF-8 if neither is there. Input given as characters (a
 * String, a Reader) is taken as it is. Output is always UTF-8.
 *
 * The object is thread-safe; documents are immutable and can be shared freely.
 * Streams passed in are not closed.
 */
object OpenTransXml {

    /**
     * Reads any of the ten openTRANS document types. The document may be the
     * root element itself or wrapped in an `<OPENTRANS>` element.
     *
     * @throws OpenTransValidationException if [xml] is not valid openTRANS 2.1
     */
    fun read(xml: String): OpenTransDocument {
        OpenTransSchema.validate(xml)
        return XmlIn.read(xml) {
            if (name == OPENTRANS) {
                var document: OpenTransDocument? = null
                children { document = readDocument() }
                document!!
            } else {
                readDocument()
            }
        }
    }

    /** Reads a document from XML bytes, decoded as the XML declares. */
    fun read(bytes: ByteArray): OpenTransDocument = read(decode(bytes))

    /** Reads a document from a file, decoded as the XML declares. */
    fun read(path: Path): OpenTransDocument = read(Files.readAllBytes(path))

    /** Reads a document from a byte stream to its end, decoded as the XML declares. Does not close [input]. */
    fun read(input: InputStream): OpenTransDocument = read(input.readAllBytes())

    /** Reads a document from already decoded characters to their end. Does not close [reader]. */
    fun read(reader: Reader): OpenTransDocument = read(reader.readText())

    /**
     * Reads a document of the expected type, e.g. `readAs<Order>(xml)`.
     *
     * @throws DocumentTypeException if [xml] holds another document type
     */
    inline fun <reified T : OpenTransDocument> readAs(xml: String): T = expect(read(xml))

    inline fun <reified T : OpenTransDocument> readAs(bytes: ByteArray): T = expect(read(bytes))

    inline fun <reified T : OpenTransDocument> readAs(path: Path): T = expect(read(path))

    inline fun <reified T : OpenTransDocument> readAs(input: InputStream): T = expect(read(input))

    inline fun <reified T : OpenTransDocument> readAs(reader: Reader): T = expect(read(reader))

    @PublishedApi
    internal inline fun <reified T : OpenTransDocument> expect(document: OpenTransDocument): T =
        document as? T ?: throw DocumentTypeException(T::class.simpleName.orEmpty(), document::class.simpleName.orEmpty())

    /**
     * Writes a document as XML text.
     *
     * @throws OpenTransValidationException if the document violates the XSD,
     *   e.g. an ORDER_ID longer than 250 characters
     */
    fun write(document: OpenTransDocument): String = writeDocument(document).also(OpenTransSchema::validate)

    /** Writes a document to a file, UTF-8 encoded. */
    fun write(document: OpenTransDocument, path: Path) {
        Files.write(path, write(document).toByteArray(Charsets.UTF_8))
    }

    /** Writes a document to a byte stream, UTF-8 encoded. Does not close [output]. */
    fun write(document: OpenTransDocument, output: OutputStream) {
        output.write(write(document).toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** Writes a document to a character stream. Does not close [writer]. */
    fun write(document: OpenTransDocument, writer: Writer) {
        writer.write(write(document))
        writer.flush()
    }

    private val OPENTRANS = QName(OT, "OPENTRANS")

    // --- decoding ---

    private val prologFactory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    /**
     * Decodes XML bytes the way the XML itself says: a UTF-16 byte order mark
     * wins, then the declared encoding, then UTF-8. A UTF-8 byte order mark
     * is dropped.
     */
    private fun decode(bytes: ByteArray): String {
        val charset = try {
            val reader = prologFactory.createXMLStreamReader(ByteArrayInputStream(bytes))
            try {
                val detected = reader.encoding
                val declared = reader.characterEncodingScheme
                val name = if (detected != null && detected.startsWith("UTF-16", ignoreCase = true)) detected else declared ?: detected ?: "UTF-8"
                Charset.forName(name)
            } finally {
                reader.close()
            }
        } catch (e: XMLStreamException) {
            throw OpenTransValidationException(listOf("not well-formed: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            throw OpenTransValidationException(listOf("unsupported encoding: ${e.message}"))
        }
        return String(bytes, charset).removePrefix("﻿")
    }
}
