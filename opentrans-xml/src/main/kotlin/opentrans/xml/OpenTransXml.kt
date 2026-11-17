package opentrans.xml

import opentrans.model.OpenTransDocument
import java.nio.file.Path
import javax.xml.namespace.QName
import kotlin.io.path.readText
import kotlin.io.path.writeText

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

    fun read(path: Path): OpenTransDocument = read(path.readText())

    /**
     * Reads a document of the expected type, e.g. `readAs<Order>(xml)`.
     *
     * @throws IllegalArgumentException if [xml] holds another document type
     */
    inline fun <reified T : OpenTransDocument> readAs(xml: String): T {
        val document = read(xml)
        return document as? T
            ?: throw IllegalArgumentException("Expected ${T::class.simpleName}, found ${document::class.simpleName}")
    }

    inline fun <reified T : OpenTransDocument> readAs(path: Path): T = readAs(path.readText())

    /**
     * Writes a document as XML text.
     *
     * @throws OpenTransValidationException if the document violates the XSD,
     *   e.g. an ORDER_ID longer than 250 characters
     */
    fun write(document: OpenTransDocument): String = writeDocument(document).also(OpenTransSchema::validate)

    fun write(document: OpenTransDocument, path: Path) {
        path.writeText(write(document))
    }

    private val OPENTRANS = QName(OT, "OPENTRANS")
}
