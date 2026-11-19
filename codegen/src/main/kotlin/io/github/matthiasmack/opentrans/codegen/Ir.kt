package io.github.matthiasmack.opentrans.codegen

import javax.xml.namespace.QName

/*
 * Intermediate representation between the XSD and the emitted Kotlin.
 *
 * It holds everything the model, reader and writer emitters need: Kotlin
 * names and types, plus how each property maps back to XML (binding).
 */

enum class ScalarKind { STRING, DECIMAL, INT, BOOLEAN, DATETIME }

sealed interface KType {
    data class Scalar(val kind: ScalarKind) : KType
    data class Enum(val def: EnumDef) : KType
    data class Class(val def: ClassDef) : KType
    data class Choice(val def: ChoiceDef) : KType
    data object Raw : KType
}

sealed interface Card {
    data object Required : Card
    data object Optional : Card
    data class Many(val min: Int) : Card
}

/** How a property is represented in XML. */
sealed interface Binding {
    /** A child element; [wrapper] is the flattened wrapper element around a list, if any. */
    data class Element(val name: QName, val wrapper: QName? = null) : Binding
    /** An attribute; [namespace] is null for unqualified attributes (the usual case). */
    data class Attribute(val name: String, val namespace: String? = null) : Binding
    /** The simple content of the element itself (property `value`). */
    data object Text : Binding
    /** An xsd:choice; the variants carry their own bindings. */
    data object Choice : Binding
    /** A repeated xsd:sequence; each list entry is one repetition. */
    data object Group : Binding
}

class Prop(val name: String, val type: KType, val card: Card, val binding: Binding) {
    /** XSD restrictions on the value (or on `value` of a simple-content class). */
    var facets: Facets? = null
}

/**
 * XSD facets of a simple type. Every pattern must match (one per derivation
 * step); lengths count characters; bounds and digits apply to numbers.
 */
data class Facets(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val patterns: List<String> = emptyList(),
    val enumeration: List<String> = emptyList(),
    val minInclusive: String? = null,
    val maxInclusive: String? = null,
    val minExclusive: String? = null,
    val maxExclusive: String? = null,
    val totalDigits: Int? = null,
    val fractionDigits: Int? = null,
) {
    val isEmpty get() = this == Facets()
}

class EnumDef(val name: String, val origin: String, val values: List<String>)

/** How a choice variant maps to XML. */
sealed interface VariantKind {
    /** The variant is the element's own class. */
    data class Direct(val element: QName) : VariantKind
    /** Single simple or shared-class element, held in property `value`. */
    data class Wrapped(val element: QName) : VariantKind
    /** Repeated element, held in a list property. */
    data class Repeated(val element: QName) : VariantKind
    /** A sequence; the variant class has one property per element. */
    data object Sequence : VariantKind
}

class Variant(val cls: ClassDef, val kind: VariantKind)

class ChoiceDef(val name: String, val origin: String) {
    val variants = mutableListOf<Variant>()
}

class ClassDef(val name: String, val origin: String, val element: QName?) {
    val props = mutableListOf<Prop>()
    val implements = linkedSetOf<ChoiceDef>()

    /** Attributes with a fixed value (e.g. version="2.1"); written, never modelled. */
    val fixedAttributes = linkedMapOf<String, String>()
}

class Ir(
    val classes: List<ClassDef>,
    val enums: List<EnumDef>,
    val choices: List<ChoiceDef>,
    val roots: List<ClassDef>,
)
