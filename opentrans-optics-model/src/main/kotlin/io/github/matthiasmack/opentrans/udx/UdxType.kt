package io.github.matthiasmack.opentrans.udx

import io.github.matthiasmack.opentrans.model.DateTime
import io.github.matthiasmack.opentrans.model.OpenTransException
import io.github.matthiasmack.opentrans.model.XmlElement
import io.github.matthiasmack.opentrans.model.XmlNode
import io.github.matthiasmack.opentrans.model.XmlText
import java.math.BigDecimal
import java.time.LocalDate
import javax.xml.namespace.QName

/**
 * A user-defined UDX type: one Kotlin description of a UDX element, from
 * which reading, writing, validation and optics follow. No XSD needed.
 *
 * ```
 * data class ProjectInfo(val project: String, val priority: Priority?, val tags: List<String>)
 *
 * object ProjectInfoUdx : UdxType<ProjectInfo>("UDX.ACME.PROJECT", "urn:acme:udx") {
 *     private val project  = required("PROJECT", ProjectInfo::project, string(maxLength = 50))
 *     private val priority = optional("PRIORITY", ProjectInfo::priority, enum<Priority>())
 *     private val tags     = list("TAG", ProjectInfo::tags, string())
 *     override fun create(v: Values) = ProjectInfo(v[project], v[priority], v[tags])
 * }
 * ```
 *
 * Rules:
 * - Child elements are written in declaration order and live in the namespace
 *   of their parent element. Attributes have no namespace.
 * - Reading is strict: unknown child elements or attributes, text in an
 *   element that has fields, missing required values, too many or too few
 *   repetitions and invalid values all throw [UdxViolationException], with
 *   the path in the message. Child elements may come in any order.
 * - Writing checks the same rules, so an invalid value never enters a document.
 * - A UdxType can be the type of a field of another UdxType (nested elements);
 *   then the field gives the element name.
 * - An element that holds just a value (`<UDX.DROPSHIPMENT>true</UDX.DROPSHIPMENT>`)
 *   declares a [text] field, possibly next to attributes; [UdxType.value]
 *   creates such a type in one line.
 */
abstract class UdxType<T>(
    val name: QName,
) : UdxValueType<T> {
    constructor(localName: String, namespace: String = "") : this(QName(namespace, localName))

    private val fields = mutableListOf<Field<T, *>>()

    /** Builds the value from the fields that were read: `ProjectInfo(v[project], v[priority])`. */
    protected abstract fun create(v: Values): T

    /** The values read for one element, looked up by field. */
    class Values internal constructor(
        private val values: Map<Field<*, *>, Any?>,
    ) {
        @Suppress("UNCHECKED_CAST")
        operator fun <V> get(field: Field<*, V>): V = values.getValue(field) as V
    }

    // --- fields ---

    /** A field of a UDX type; declare it with [required], [optional], [list], [attribute], [optionalAttribute] or [text]. */
    class Field<T, V> internal constructor(
        internal val localName: String,
        internal val kind: Kind,
        internal val get: (T) -> V,
        internal val type: UdxValueType<*>,
        internal val min: Int,
        internal val max: Int?,
    ) {
        internal enum class Kind { ELEMENT, ATTRIBUTE, TEXT }
    }

    /** Child element that must occur exactly once. */
    protected fun <V> required(
        name: String,
        get: (T) -> V,
        type: UdxValueType<V>,
    ): Field<T, V> = add(Field(name, Field.Kind.ELEMENT, get, type, 1, 1))

    /** Child element that may be missing; missing means `null`. */
    protected fun <V : Any> optional(
        name: String,
        get: (T) -> V?,
        type: UdxValueType<V>,
    ): Field<T, V?> = add(Field(name, Field.Kind.ELEMENT, get, type, 0, 1))

    /** Repeated child element, between [min] and [max] times (no upper limit when null). */
    protected fun <V> list(
        name: String,
        get: (T) -> List<V>,
        type: UdxValueType<V>,
        min: Int = 0,
        max: Int? = null,
    ): Field<T, List<V>> {
        require(min >= 0 && (max == null || max >= maxOf(min, 1))) { "$name: invalid repetition $min..$max" }
        return add(Field(name, Field.Kind.ELEMENT, get, type, min, max))
    }

    /** Attribute that must be present. */
    protected fun <V> attribute(
        name: String,
        get: (T) -> V,
        type: SimpleType<V>,
    ): Field<T, V> = add(Field(name, Field.Kind.ATTRIBUTE, get, type, 1, 1))

    /** Attribute that may be missing; missing means `null`. */
    protected fun <V : Any> optionalAttribute(
        name: String,
        get: (T) -> V?,
        type: SimpleType<V>,
    ): Field<T, V?> = add(Field(name, Field.Kind.ATTRIBUTE, get, type, 0, 1))

    /**
     * The element's own text: `<UDX.DROPSHIPMENT>true</UDX.DROPSHIPMENT>`. Can be
     * combined with attributes, not with child elements.
     */
    protected fun <V> text(
        get: (T) -> V,
        type: SimpleType<V>,
    ): Field<T, V> = add(Field("#text", Field.Kind.TEXT, get, type, 1, 1))

    private fun <V> add(field: Field<T, V>): Field<T, V> {
        require(
            fields.none {
                it.localName == field.localName && it.kind == field.kind
            },
        ) { "${name.localPart}: ${field.localName} is declared twice" }
        val kinds = (fields.map { it.kind } + field.kind).toSet()
        require(
            !(Field.Kind.TEXT in kinds && Field.Kind.ELEMENT in kinds),
        ) { "${name.localPart}: text and child elements cannot be combined" }
        fields += field
        return field
    }

    // --- simple types, for use in field declarations ---

    protected fun string(
        minLength: Int? = null,
        maxLength: Int? = null,
        pattern: Regex? = null,
    ): SimpleType<String> = SimpleTypes.string(minLength, maxLength, pattern)

    protected fun int(
        min: Int? = null,
        max: Int? = null,
    ): SimpleType<Int> = SimpleTypes.int(min, max)

    protected fun decimal(
        min: BigDecimal? = null,
        max: BigDecimal? = null,
    ): SimpleType<BigDecimal> = SimpleTypes.decimal(min, max)

    /** `true` / `false`, read in any case like bmecat:dtBOOLEAN. */
    protected fun boolean(): SimpleType<Boolean> = SimpleTypes.boolean()

    /** ISO date, `2026-11-17`. */
    protected fun date(): SimpleType<LocalDate> = SimpleTypes.date()

    /** bmecat:dtDATETIME, as in the rest of the model. */
    protected fun dateTime(): SimpleType<DateTime> = SimpleTypes.dateTime()

    /** Enum written as [xml] of each constant (its name by default). */
    protected inline fun <reified E : Enum<E>> enum(noinline xml: (E) -> String = { it.name }): SimpleType<E> = SimpleTypes.enum(xml)

    protected fun <E : Enum<E>> enumType(
        values: List<E>,
        xml: (E) -> String,
    ): SimpleType<E> = SimpleTypes.enumType(values, xml)

    // --- reading and writing ---

    /**
     * Reads a value from its element.
     *
     * @throws UdxViolationException if the element does not match this type
     */
    fun read(element: XmlElement): T {
        if (element.name !=
            name
        ) {
            throw UdxViolationException("${element.name.localPart}: expected ${name.localPart} in namespace '${name.namespaceURI}'")
        }
        return readBody(element, name.localPart)
    }

    /**
     * Writes a value as its element.
     *
     * @throws UdxViolationException if the value breaks a rule of this type
     */
    fun write(value: T): XmlElement = writeBody(name, value, name.localPart)

    internal fun readBody(
        element: XmlElement,
        path: String,
    ): T {
        val ns = element.name.namespaceURI
        val elementFields = fields.filter { it.kind == Field.Kind.ELEMENT }.associateBy { QName(ns, it.localName) }
        val attributeFields = fields.filter { it.kind == Field.Kind.ATTRIBUTE }.associateBy { QName(it.localName) }

        element.elements
            .firstOrNull {
                it.name !in elementFields
            }?.let { throw UdxViolationException("$path: unknown element ${it.name.localPart}") }
        element.attributes.keys
            .firstOrNull {
                it !in attributeFields
            }?.let { throw UdxViolationException("$path: unknown attribute ${it.localPart}") }
        val hasText = fields.any { it.kind == Field.Kind.TEXT }
        if (!hasText &&
            element.text.isNotBlank()
        ) {
            throw UdxViolationException("$path: text is not allowed here, got '${element.text.trim()}'")
        }

        val values = HashMap<Field<*, *>, Any?>()
        for (field in fields) {
            val fieldPath =
                when (field.kind) {
                    Field.Kind.ATTRIBUTE -> "$path/@${field.localName}"
                    Field.Kind.TEXT -> path
                    Field.Kind.ELEMENT -> "$path/${field.localName}"
                }
            values[field] =
                when (field.kind) {
                    Field.Kind.TEXT -> {
                        if (element.text.isBlank()) throw UdxViolationException("$path: a value is missing")
                        (field.type as SimpleType<*>).parse(element.text, path)
                    }

                    Field.Kind.ATTRIBUTE -> {
                        val text = element.attribute(field.localName)
                        when {
                            text != null -> (field.type as SimpleType<*>).parse(text, fieldPath)
                            field.min == 1 -> throw UdxViolationException("$fieldPath is missing")
                            else -> null
                        }
                    }

                    Field.Kind.ELEMENT -> {
                        val children = element.elements.filter { it.name == QName(ns, field.localName) }
                        checkCount(field, children.size, fieldPath)
                        val read = children.map { readValue(field.type, it, fieldPath) }
                        if (field.max == 1) read.singleOrNull() else read
                    }
                }
        }
        return try {
            create(Values(values))
        } catch (e: UdxViolationException) {
            throw e
        } catch (e: OpenTransException) {
            // e.g. a model value created in create() that breaks an XSD facet
            throw UdxViolationException("$path: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw UdxViolationException("$path: ${e.message}", e)
        }
    }

    internal fun writeBody(
        elementName: QName,
        value: T,
        path: String,
    ): XmlElement {
        val ns = elementName.namespaceURI
        val attributes = LinkedHashMap<QName, String>()
        val children = mutableListOf<XmlNode>()
        for (field in fields) {
            val fieldValue = field.get(value)
            when (field.kind) {
                Field.Kind.TEXT -> {
                    children += XmlText(formatValue(field.type as SimpleType<*>, fieldValue, path))
                }

                Field.Kind.ATTRIBUTE -> {
                    val fieldPath = "$path/@${field.localName}"
                    if (fieldValue !=
                        null
                    ) {
                        attributes[QName(field.localName)] = formatValue(field.type as SimpleType<*>, fieldValue, fieldPath)
                    } else if (field.min == 1) {
                        throw UdxViolationException("$fieldPath is missing")
                    }
                }

                Field.Kind.ELEMENT -> {
                    val fieldPath = "$path/${field.localName}"
                    val items = if (field.max == 1) listOfNotNull(fieldValue) else fieldValue as List<*>
                    checkCount(field, items.size, fieldPath)
                    val childName = QName(ns, field.localName, elementName.prefix)
                    items.forEach { children += writeValue(field.type, childName, it, fieldPath) }
                }
            }
        }
        return XmlElement(elementName, attributes, children)
    }

    private fun checkCount(
        field: Field<*, *>,
        count: Int,
        path: String,
    ) {
        when {
            count < field.min -> throw UdxViolationException(
                if (field.min == 1 &&
                    field.max == 1
                ) {
                    "$path is missing"
                } else {
                    "$path: at least ${field.min} times, got $count"
                },
            )

            field.max != null && count > field.max -> throw UdxViolationException(
                if (field.max ==
                    1
                ) {
                    "$path: only once, got $count"
                } else {
                    "$path: at most ${field.max} times, got $count"
                },
            )
        }
    }

    private fun readValue(
        type: UdxValueType<*>,
        element: XmlElement,
        path: String,
    ): Any? =
        when (type) {
            is SimpleType<*> -> {
                if (element.elements.isNotEmpty() ||
                    element.attributes.isNotEmpty()
                ) {
                    throw UdxViolationException("$path: only text is allowed here")
                }
                type.parse(element.text, path)
            }

            is UdxType<*> -> {
                type.readBody(element, path)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun writeValue(
        type: UdxValueType<*>,
        name: QName,
        value: Any?,
        path: String,
    ): XmlElement =
        when (type) {
            is SimpleType<*> -> XmlElement(name, children = listOf(XmlText(formatValue(type, value, path))))
            is UdxType<*> -> (type as UdxType<Any?>).writeBody(name, value, path)
        }

    @Suppress("UNCHECKED_CAST")
    private fun formatValue(
        type: SimpleType<*>,
        value: Any?,
        path: String,
    ): String = (type as SimpleType<Any?>).format(value, path)

    override fun toString() = "UdxType(${name.localPart})"

    companion object {
        /**
         * A UDX element that holds just a value, e.g.
         * `val DROPSHIPMENT = UdxType.value("UDX.DROPSHIPMENT", SimpleTypes.boolean())`.
         */
        fun <V> value(
            localName: String,
            type: SimpleType<V>,
            namespace: String = "",
        ): UdxType<V> =
            object : UdxType<V>(localName, namespace) {
                private val value = text({ it }, type)

                override fun create(v: Values): V = v[value]
            }
    }
}

/**
 * The simple types for UDX fields, also usable outside a [UdxType] declaration
 * (e.g. for [UdxType.value]). Inside a declaration the same factories are
 * available without the prefix.
 */
object SimpleTypes {
    fun string(
        minLength: Int? = null,
        maxLength: Int? = null,
        pattern: Regex? = null,
    ): SimpleType<String> =
        SimpleType("string", { it }, { it }) { value ->
            val length = value.codePointCount(0, value.length)
            when {
                minLength != null && length < minLength -> "at least $minLength characters, got $length"
                maxLength != null && length > maxLength -> "at most $maxLength characters, got $length"
                pattern != null && !pattern.matches(value) -> "'$value' does not match ${pattern.pattern}"
                else -> null
            }
        }

    fun int(
        min: Int? = null,
        max: Int? = null,
    ): SimpleType<Int> =
        SimpleType("integer", { it.trim().toInt() }, Int::toString) { value ->
            when {
                min != null && value < min -> "at least $min, got $value"
                max != null && value > max -> "at most $max, got $value"
                else -> null
            }
        }

    fun decimal(
        min: BigDecimal? = null,
        max: BigDecimal? = null,
    ): SimpleType<BigDecimal> =
        SimpleType("decimal", { BigDecimal(it.trim()) }, BigDecimal::toPlainString) { value ->
            when {
                min != null && value < min -> "at least $min, got $value"
                max != null && value > max -> "at most $max, got $value"
                else -> null
            }
        }

    /** `true` / `false`, read in any case like bmecat:dtBOOLEAN. */
    fun boolean(): SimpleType<Boolean> =
        SimpleType("boolean", {
            when (it.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("'$it' is not true or false")
            }
        }, Boolean::toString)

    /** ISO date, `2026-11-17`. */
    fun date(): SimpleType<LocalDate> = SimpleType("date", { LocalDate.parse(it.trim()) }, LocalDate::toString)

    /** bmecat:dtDATETIME, as in the rest of the model. */
    fun dateTime(): SimpleType<DateTime> = SimpleType("date-time", { DateTime(it.trim()) }, DateTime::value)

    /** Enum written as [xml] of each constant (its name by default). */
    inline fun <reified E : Enum<E>> enum(noinline xml: (E) -> String = { it.name }): SimpleType<E> =
        enumType(enumValues<E>().toList(), xml)

    fun <E : Enum<E>> enumType(
        values: List<E>,
        xml: (E) -> String,
    ): SimpleType<E> {
        val byXml = values.associateBy(xml)
        return SimpleType(
            "one of ${byXml.keys}",
            { byXml[it.trim()] ?: throw IllegalArgumentException("'$it' is not one of ${byXml.keys}") },
            xml,
        )
    }
}

/** Type of a UDX field: a [SimpleType] (text) or another [UdxType] (nested element). */
sealed interface UdxValueType<V>

/**
 * A text value: how to parse and format it, and which rule it must follow.
 * Use the factories inside a [UdxType] (`string(maxLength = 50)`, `decimal()`,
 * ...) or build your own: `SimpleType("currency", { Currency.getInstance(it) }, Currency::getCurrencyCode)`.
 *
 * @param check returns an error message for an invalid value, or null
 */
class SimpleType<V>(
    private val description: String,
    private val parse: (String) -> V,
    private val format: (V) -> String,
    private val check: (V) -> String? = { null },
) : UdxValueType<V> {
    internal fun parse(
        text: String,
        path: String,
    ): V {
        val value =
            try {
                parse(text)
            } catch (e: RuntimeException) {
                throw UdxViolationException("$path: '$text' is not a valid $description", e)
            }
        check(value)?.let { throw UdxViolationException("$path: $it") }
        return value
    }

    internal fun format(
        value: V,
        path: String,
    ): String {
        check(value)?.let { throw UdxViolationException("$path: $it") }
        return format(value)
    }
}

/** A UDX element or value that breaks a rule of its [UdxType]; the message starts with the path. */
class UdxViolationException(
    message: String,
    cause: Throwable? = null,
) : OpenTransException(message, cause)
