package io.github.matthiasmack.opentrans.model

/*
 * Every problem with a document or its data is an [OpenTransException], so a
 * single catch at the system boundary covers them all:
 *
 * - OpenTransValidationException: XML that is not well-formed or not valid openTRANS
 * - FacetViolationException:      a value the XSD forbids
 * - IncompleteDocumentException:  a required value is missing
 * - DocumentTypeException:        another document type than expected
 * - UdxViolationException:        UDX content that does not match its UdxType
 *
 * Wrong use of the API (a DSL block given twice, a contradictory UdxType
 * declaration) is a programming error and throws the standard
 * IllegalArgumentException / IllegalStateException instead.
 */

/** Base class of all exceptions about documents and their data. */
open class OpenTransException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * XML that is not well-formed or does not conform to the openTRANS 2.1 schema,
 * on reading or writing. [errors] lists every problem found, with line and
 * column where the validator reports them.
 */
class OpenTransValidationException(val errors: List<String>) :
    OpenTransException("Not valid openTRANS 2.1 XML:\n" + errors.joinToString("\n") { "  - $it" })

/**
 * A value that violates an XSD facet (length, pattern, allowed values, range,
 * digits), thrown where the value is created. The message starts with the XML
 * path: `ORDER_INFO/ORDER_ID: at most 250 characters, got 251`.
 */
class FacetViolationException(message: String) : OpenTransException(message)

/**
 * A required value is missing, e.g. a builder without ORDER_ID or an order
 * without a supplier. [element] is the XML path of what is missing
 * (`ORDER_INFO/ORDER_ID`), for mapping the error back to your own data.
 */
class IncompleteDocumentException(val element: String, message: String) : OpenTransException(message)

/** A document of another type than expected, e.g. `readAs<Order>` on an INVOICE. */
class DocumentTypeException(val expected: String, val found: String) :
    OpenTransException("Expected $expected, found $found")
