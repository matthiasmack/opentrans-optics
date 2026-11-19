package io.github.matthiasmack.opentrans.codegen

import org.apache.xerces.xs.XSAttributeUse
import org.apache.xerces.xs.XSComplexTypeDefinition
import org.apache.xerces.xs.XSConstants
import org.apache.xerces.xs.XSElementDeclaration
import org.apache.xerces.xs.XSModel
import org.apache.xerces.xs.XSModelGroup
import org.apache.xerces.xs.XSParticle
import org.apache.xerces.xs.XSSimpleTypeDefinition
import org.apache.xerces.xs.XSTypeDefinition
import org.apache.xerces.xs.XSWildcard
import java.util.IdentityHashMap
import javax.xml.namespace.QName

const val OT_NS = "http://www.opentrans.org/XMLSchema/2.1"
const val BMECAT_NS = "http://www.bmecat.org/bmecat/2005"
const val XSD_NS = "http://www.w3.org/2001/XMLSchema"
const val XMLDSIG_NS = "http://www.w3.org/2000/09/xmldsig#"

val DOCUMENT_ROOTS = listOf(
    "RFQ", "QUOTATION", "ORDER", "ORDERCHANGE", "ORDERRESPONSE", "DISPATCHNOTIFICATION",
    "RECEIPTACKNOWLEDGEMENT", "INVOICE", "INVOICELIST", "REMITTANCEADVICE",
)

/**
 * Turns the XSModel into the [Ir]. The input is already resolved (references,
 * imports, derivation). Mapping rules:
 *
 * - **Classes.** One data class per global element with complex content,
 *   named after the element (`ORDER_ITEM` -> `OrderItem`). A named complex
 *   type becomes one shared class (`typePARTY_ID` -> `PartyId`). An element
 *   that only restricts a named simple-content type reuses its class; the
 *   stricter facets are checked in the containing class.
 * - **Name clashes.** openTRANS elements keep the plain name; a reachable
 *   BMEcat element with the same local name gets a `Bmecat` prefix. All names
 *   come from [Names] and are made unique by [NameRegistry].
 * - **Properties.** Required -> non-null, optional -> nullable with default
 *   `null`, repeated -> `List` with default `emptyList()` and a plural name.
 *   Attributes are properties too. A required attribute with a single allowed
 *   value (`version="2.1"`) is fixed and not modelled.
 * - **Simple content.** string -> `String`, decimal -> `BigDecimal`, integer
 *   -> `Int`, dtBOOLEAN -> `Boolean`, dtDATETIME -> `DateTime`. Simple content
 *   with attributes -> a small class with `value` plus the attributes.
 * - **Enumerations.** Closed enumerations -> Kotlin enum with the XML value.
 *   Patterns ending in `|\w{1,n}` are open -> `String`. Large code lists
 *   ([Names.codeLists]) stay `String`, with their values checked as a facet.
 * - **Wrappers.** An element without attributes whose content is exactly one
 *   repeated child is flattened into a `List` property of its parent.
 * - **Choices.** Each xsd:choice -> a sealed interface (`<Parent>Choice`
 *   unless overridden). Element with complex content -> its class implements
 *   the interface; simple element -> wrapper class; repeated element -> list
 *   class shared by every choice that uses it; sequence -> class with the
 *   sequence's properties. Optional choice -> nullable property.
 * - **Repeated sequences** (ADDRESS: EMAIL, PUBLIC_KEY*) -> list of a small
 *   class for one repetition.
 * - **Free content.** xsd:anyType (UDX) and the xmldsig subtree -> `XmlContent`.
 * - **Facets** (length, pattern, allowed values, range, digits) -> checks in
 *   the generated `init` blocks.
 */
class IrBuilder(private val model: XSModel) {
    private val typeNames = NameRegistry(Names.reserved + DOCUMENT_INTERFACE)
    private val classesByKey = mutableMapOf<QName, ClassDef>()
    private val wrappersByElement = mutableMapOf<QName, ClassDef>()
    private val repeatedVariantsByElement = mutableMapOf<QName, ClassDef>()
    private val enumsByType = IdentityHashMap<XSSimpleTypeDefinition, EnumDef>()
    private val classes = mutableListOf<ClassDef>()
    private val enums = mutableListOf<EnumDef>()
    private val choices = mutableListOf<ChoiceDef>()
    private val pending = ArrayDeque<() -> Unit>()
    private val facetsByElement = mutableMapOf<QName, Facets>()

    fun build(): Ir {
        val roots = DOCUMENT_ROOTS.map { name ->
            val element = model.getElementDeclaration(name, OT_NS) ?: error("document root $name missing")
            classForElement(element)
        }
        while (pending.isNotEmpty()) pending.removeFirst().invoke()
        for (prop in classes.flatMap { it.props }) {
            val binding = prop.binding as? Binding.Element ?: continue
            facetsByElement[binding.name]?.let { prop.facets = it }
        }
        Names.unusedOverrides().takeIf { it.isNotEmpty() }?.let { error("overrides that match nothing: $it") }
        return Ir(classes, enums, choices, roots)
    }

    // --- classes ---

    private fun classForElement(e: XSElementDeclaration): ClassDef {
        val t = e.typeDefinition as XSComplexTypeDefinition
        if (!t.anonymous) return classForNamedType(t)
        reusableBase(t)?.let { return classForNamedType(it) }

        return classesByKey.getOrPut(e.qname) {
            val name = typeNames.claim(Names.classOverride(e.name) ?: elementBaseName(e))
            newClass(name, e.name, e.qname).also { cls -> pending += { fill(cls, t) } }
        }
    }

    /** Class-style name of an element; BMEcat elements that clash with an openTRANS name get a prefix. */
    private fun elementBaseName(e: XSElementDeclaration): String {
        val clashesWithOpenTrans = e.namespace == BMECAT_NS && model.getElementDeclaration(e.name, OT_NS) != null
        return (if (clashesWithOpenTrans) "Bmecat" else "") + Names.className(e.name)
    }

    private fun classForNamedType(t: XSComplexTypeDefinition): ClassDef =
        classesByKey.getOrPut(QName(t.namespace, t.name)) {
            val name = typeNames.claim(Names.classOverride(t.name) ?: Names.className(t.name))
            newClass(name, t.name, null).also { cls -> pending += { fill(cls, t) } }
        }

    /**
     * An anonymous simple-content type that only restricts a named one
     * (e.g. bmecat:NAME restricts dtMLSTRING with a max length) reuses the
     * named type's class: same attributes, only stricter facets.
     */
    private fun reusableBase(t: XSComplexTypeDefinition): XSComplexTypeDefinition? {
        if (t.contentType != XSComplexTypeDefinition.CONTENTTYPE_SIMPLE) return null
        if (t.derivationMethod != XSConstants.DERIVATION_RESTRICTION) return null
        val base = t.baseType as? XSComplexTypeDefinition ?: return null
        if (base.anonymous || base.name == "anyType") return null
        return base.takeIf { attributeNames(it) == attributeNames(t) }
    }

    private fun newClass(name: String, origin: String, element: QName?) =
        ClassDef(name, origin, element).also { classes += it }

    private fun fill(cls: ClassDef, t: XSComplexTypeDefinition) {
        val props = NameRegistry()
        var choiceCount = 0

        fun addProp(defaultName: String, type: KType, card: Card, binding: Binding) {
            val name = Names.property(cls.name, defaultName)
            cls.props += Prop(props.claim(name), type, card, binding)
        }

        fun walk(p: XSParticle, forcedOptional: Boolean) {
            when (val term = p.term) {
                is XSElementDeclaration -> addElement(p, term, forcedOptional, ::addProp)
                is XSModelGroup -> when {
                    term.compositor == XSModelGroup.COMPOSITOR_CHOICE ->
                        addChoice(cls, p, term, ++choiceCount, forcedOptional, ::addProp)
                    p.repeated -> addRepeatedGroup(cls, p, term, ::addProp)
                    else -> term.particleList.forEach { walk(it, forcedOptional || p.minOccurs == 0) }
                }
                is XSWildcard -> error("wildcard in ${cls.origin} should have been mapped to XmlContent")
            }
        }

        when (t.contentType) {
            XSComplexTypeDefinition.CONTENTTYPE_SIMPLE -> {
                addProp("value", simpleType(t.simpleType, cls.name + "Value", "${cls.origin} content"), Card.Required, Binding.Text)
                cls.props.last().facets = facets(t.simpleType)
            }
            XSComplexTypeDefinition.CONTENTTYPE_ELEMENT -> walk(t.particle, false)
            XSComplexTypeDefinition.CONTENTTYPE_EMPTY -> Unit
            else -> error("mixed content in ${cls.origin} should have been mapped to XmlContent")
        }

        for (use in t.attributeUseList) {
            val decl = use.attrDeclaration
            if (use.constraintType == XSConstants.VC_FIXED) {
                cls.fixedAttributes[decl.name] = use.valueConstraintValue.normalizedValue
                continue
            }
            // A required attribute with exactly one allowed value (version="2.1") is fixed in practice.
            val only = decl.typeDefinition.lexicalEnumeration.takeIf { it.length == 1 }?.item(0)
            if (use.required && only != null) {
                cls.fixedAttributes[decl.name] = only
                continue
            }
            val type = simpleType(decl.typeDefinition, cls.name + Names.className(decl.name), "${cls.origin}/@${decl.name}")
            addProp(Names.propertyName(decl.name), type, if (use.required) Card.Required else Card.Optional, Binding.Attribute(decl.name, decl.namespace?.takeIf { it.isNotEmpty() }))
            cls.props.last().facets = facets(decl.typeDefinition)
        }
    }

    private fun addElement(
        p: XSParticle, e: XSElementDeclaration, forcedOptional: Boolean,
        addProp: (String, KType, Card, Binding) -> Unit,
    ) {
        val wrapped = wrappedChild(e)
        if (wrapped != null && !p.repeated) {
            val (childParticle, child) = wrapped
            val min = if (p.minOccurs == 0 || forcedOptional) 0 else childParticle.minOccurs
            addProp(Names.plural(Names.propertyName(child.name)), elementType(child), Card.Many(min), Binding.Element(child.qname, wrapper = e.qname))
            return
        }
        val card = when {
            p.repeated -> Card.Many(if (forcedOptional) 0 else p.minOccurs)
            p.minOccurs == 0 || forcedOptional -> Card.Optional
            else -> Card.Required
        }
        val name = Names.propertyName(e.name).let { if (card is Card.Many) Names.plural(it) else it }
        addProp(name, elementType(e), card, Binding.Element(e.qname))
    }

    private fun addRepeatedGroup(
        owner: ClassDef, p: XSParticle, group: XSModelGroup,
        addProp: (String, KType, Card, Binding) -> Unit,
    ) {
        val first = group.firstElement() ?: error("empty repeated group in ${owner.origin}")
        val entry = newClass(typeNames.claim(owner.name + Names.className(first.name)), "${owner.origin}/${first.name}+", null)
        pending += { fillFromGroup(entry, group) }
        addProp(Names.plural(Names.propertyName(first.name)), KType.Class(entry), Card.Many(p.minOccurs), Binding.Group)
    }

    private fun addChoice(
        owner: ClassDef, p: XSParticle, group: XSModelGroup, index: Int, forcedOptional: Boolean,
        addProp: (String, KType, Card, Binding) -> Unit,
    ) {
        val key = "${owner.origin}#$index"
        val choice = ChoiceDef(typeNames.claim(Names.classOverride(key) ?: (owner.name + "Choice")), key).also { choices += it }
        val alternatives = group.particleList
        val directCandidates = alternatives.mapNotNull { (it.term as? XSElementDeclaration)?.takeIf { _ -> !it.repeated } }
            .map { elementType(it) }.filterIsInstance<KType.Class>().groupingBy { it.def }.eachCount()

        var anyOptionalAlternative = false
        for (alt in alternatives) {
            if (alt.minOccurs == 0) anyOptionalAlternative = true
            when (val term = alt.term) {
                is XSElementDeclaration -> choice.variants += elementVariant(choice, alt, term, directCandidates)
                is XSModelGroup -> {
                    val first = term.firstElement() ?: error("empty sequence in choice $key")
                    val origin = "$key/${first.name}..."
                    val name = typeNames.claim(Names.classOverride(origin) ?: (owner.name + Names.className(first.name) + "Group"))
                    val cls = newClass(name, origin, null)
                    cls.implements += choice
                    pending += { fillFromGroup(cls, term) }
                    choice.variants += Variant(cls, VariantKind.Sequence)
                }
                is XSWildcard -> error("wildcard in choice $key")
            }
        }

        val card = when {
            p.repeated -> Card.Many(p.minOccurs)
            p.minOccurs == 0 || forcedOptional || anyOptionalAlternative -> Card.Optional
            else -> Card.Required
        }
        addProp(choice.name.replaceFirstChar(Char::lowercase), KType.Choice(choice), card, Binding.Choice)
    }

    private fun elementVariant(
        choice: ChoiceDef, alt: XSParticle, e: XSElementDeclaration, directCandidates: Map<ClassDef, Int>,
    ): Variant {
        val type = elementType(e)
        if (alt.repeated) {
            val cls = repeatedVariantsByElement.getOrPut(e.qname) {
                val singular = Names.className(e.name)
                val plural = Names.plural(singular).let { if (it == singular) "${singular}List" else it }
                newClass(typeNames.claim(Names.classOverride("${e.name}+") ?: plural), "${e.name}+", null).also {
                    it.props += Prop(Names.plural(Names.propertyName(e.name)), type, Card.Many(maxOf(alt.minOccurs, 1)), Binding.Element(e.qname))
                }
            }
            cls.implements += choice
            return Variant(cls, VariantKind.Repeated(e.qname))
        }
        if (type is KType.Class && type.def.element == e.qname && directCandidates[type.def] == 1) {
            type.def.implements += choice
            return Variant(type.def, VariantKind.Direct(e.qname))
        }
        val wrapper = wrappersByElement.getOrPut(e.qname) {
            newClass(typeNames.claim(Names.className(e.name)), e.name, null).also {
                it.props += Prop("value", type, Card.Required, Binding.Element(e.qname))
            }
        }
        wrapper.implements += choice
        return Variant(wrapper, VariantKind.Wrapped(e.qname))
    }

    private fun fillFromGroup(cls: ClassDef, group: XSModelGroup) {
        val props = NameRegistry()
        for (p in group.particleList) {
            val e = p.term as? XSElementDeclaration ?: error("nested group inside ${cls.origin} is not supported")
            addElement(p, e, false) { name, type, card, binding -> cls.props += Prop(props.claim(Names.property(cls.name, name)), type, card, binding) }
        }
    }

    // --- types ---

    private fun elementType(e: XSElementDeclaration): KType {
        recordFacets(e)
        return elementTypeOf(e)
    }

    /** Facets of an element's value: its simple type, or the simple content of a reused class. */
    private fun recordFacets(e: XSElementDeclaration) {
        if (e.qname in facetsByElement) return
        val f = when (val t = e.typeDefinition) {
            is XSSimpleTypeDefinition -> facets(t)
            is XSComplexTypeDefinition -> when {
                t.contentType != XSComplexTypeDefinition.CONTENTTYPE_SIMPLE -> null
                t.attributeUses.length == 0 || (t.anonymous && reusableBase(t) != null) -> facets(t.simpleType)
                else -> null
            }
            else -> null
        }
        facetsByElement[e.qname] = f ?: Facets()
    }

    private fun facets(t: XSSimpleTypeDefinition?): Facets? {
        if (t == null || t.variety != XSSimpleTypeDefinition.VARIETY_ATOMIC) return null
        fun value(facet: Short) = t.getLexicalFacetValue(facet)
        val patterns = (0 until t.lexicalPattern.length).map { t.lexicalPattern.item(it) }
        val enumeration = (0 until t.lexicalEnumeration.length).map { t.lexicalEnumeration.item(it) }
        val f = Facets(
            minLength = (value(XSSimpleTypeDefinition.FACET_MINLENGTH) ?: value(XSSimpleTypeDefinition.FACET_LENGTH))?.toInt(),
            maxLength = (value(XSSimpleTypeDefinition.FACET_MAXLENGTH) ?: value(XSSimpleTypeDefinition.FACET_LENGTH))?.toInt(),
            patterns = patterns,
            enumeration = enumeration,
            minInclusive = value(XSSimpleTypeDefinition.FACET_MININCLUSIVE),
            maxInclusive = value(XSSimpleTypeDefinition.FACET_MAXINCLUSIVE),
            minExclusive = value(XSSimpleTypeDefinition.FACET_MINEXCLUSIVE),
            maxExclusive = value(XSSimpleTypeDefinition.FACET_MAXEXCLUSIVE),
            totalDigits = value(XSSimpleTypeDefinition.FACET_TOTALDIGITS)?.toInt(),
            fractionDigits = value(XSSimpleTypeDefinition.FACET_FRACTIONDIGITS)?.toInt(),
        )
        return f.takeUnless { it.isEmpty }
    }

    private fun elementTypeOf(e: XSElementDeclaration): KType = when (val t = e.typeDefinition) {
        is XSSimpleTypeDefinition -> simpleType(t, elementBaseName(e), e.name)
        is XSComplexTypeDefinition -> when {
            isRaw(t) -> KType.Raw
            t.contentType == XSComplexTypeDefinition.CONTENTTYPE_SIMPLE && t.attributeUses.length == 0 ->
                simpleType(t.simpleType, elementBaseName(e), e.name)
            else -> KType.Class(classForElement(e))
        }
        else -> error("unexpected type for ${e.name}")
    }

    private fun simpleType(t: XSSimpleTypeDefinition, enumName: String, origin: String): KType {
        if (t.variety != XSSimpleTypeDefinition.VARIETY_ATOMIC) return KType.Scalar(ScalarKind.STRING)
        val chain = generateSequence<XSTypeDefinition>(t) { it.baseType?.takeIf { b -> b !== it } }.toList()
        val names = chain.mapNotNull { it.name?.let { n -> it.namespace to n } }

        if (names.any { it.second in Names.codeLists }) return KType.Scalar(ScalarKind.STRING)
        val values = (0 until t.lexicalEnumeration.length).map { t.lexicalEnumeration.item(it) }
        if (values.size in 1..60) {
            return KType.Enum(enumsByType.getOrPut(t) {
                EnumDef(typeNames.claim(Names.classOverride(origin) ?: enumName), origin, values).also { enums += it }
            })
        }
        if (names.any { it == (BMECAT_NS to "dtDATETIME") }) return KType.Scalar(ScalarKind.DATETIME)
        if (names.any { it == (BMECAT_NS to "dtBOOLEAN") }) return KType.Scalar(ScalarKind.BOOLEAN)
        val builtin = names.firstOrNull { it.first == XSD_NS }?.second
        return KType.Scalar(
            when (builtin) {
                "integer", "int", "long", "short", "byte", "nonNegativeInteger", "positiveInteger",
                "nonPositiveInteger", "negativeInteger", "unsignedInt", "unsignedLong", "unsignedShort" -> ScalarKind.INT
                "decimal", "float", "double" -> ScalarKind.DECIMAL
                "boolean" -> ScalarKind.BOOLEAN
                else -> ScalarKind.STRING
            },
        )
    }

    /** Content we keep as raw XML: wildcards, mixed content, or the xmldsig signature. */
    private fun isRaw(t: XSComplexTypeDefinition): Boolean {
        if (t.contentType == XSComplexTypeDefinition.CONTENTTYPE_MIXED) return true
        fun containsRaw(p: XSParticle): Boolean = when (val term = p.term) {
            is XSWildcard -> true
            is XSElementDeclaration -> term.namespace == XMLDSIG_NS
            is XSModelGroup -> term.particleList.any(::containsRaw)
            else -> false
        }
        return t.particle?.let(::containsRaw) ?: false
    }

    /** An element whose content is exactly one repeated child (min >= 1) and that has no attributes. */
    private fun wrappedChild(e: XSElementDeclaration): Pair<XSParticle, XSElementDeclaration>? {
        val t = e.typeDefinition as? XSComplexTypeDefinition ?: return null
        if (t.contentType != XSComplexTypeDefinition.CONTENTTYPE_ELEMENT || t.attributeUses.length != 0 || isRaw(t)) return null
        val group = t.particle?.takeIf { it.minOccurs == 1 && !it.repeated }?.term as? XSModelGroup ?: return null
        val only = group.particleList.singleOrNull() ?: return null
        val child = only.term as? XSElementDeclaration ?: return null
        return if (only.repeated && only.minOccurs >= 1) only to child else null
    }

    private fun attributeNames(t: XSComplexTypeDefinition) = t.attributeUseList.map { it.attrDeclaration.name }.toSet()
}

private val XSElementDeclaration.qname get() = QName(namespace, name)
private val XSParticle.repeated get() = maxOccursUnbounded || maxOccurs > 1
private val XSModelGroup.particleList get() = (0 until particles.length).map { particles.item(it) as XSParticle }
private val XSComplexTypeDefinition.attributeUseList get() = (0 until attributeUses.length).map { attributeUses.item(it) as XSAttributeUse }

private fun XSModelGroup.firstElement(): XSElementDeclaration? = particleList.firstNotNullOfOrNull {
    when (val term = it.term) {
        is XSElementDeclaration -> term
        is XSModelGroup -> term.firstElement()
        else -> null
    }
}
