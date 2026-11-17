package opentrans.codegen

import org.apache.xerces.xs.XSComplexTypeDefinition
import org.apache.xerces.xs.XSComplexTypeDefinition.CONTENTTYPE_ELEMENT
import org.apache.xerces.xs.XSComplexTypeDefinition.CONTENTTYPE_EMPTY
import org.apache.xerces.xs.XSComplexTypeDefinition.CONTENTTYPE_MIXED
import org.apache.xerces.xs.XSComplexTypeDefinition.CONTENTTYPE_SIMPLE
import org.apache.xerces.xs.XSConstants
import org.apache.xerces.xs.XSElementDeclaration
import org.apache.xerces.xs.XSModelGroup
import org.apache.xerces.xs.XSParticle
import org.apache.xerces.xs.XSSimpleTypeDefinition
import org.apache.xerces.xs.XSTerm
import org.apache.xerces.xs.XSTypeDefinition
import org.apache.xerces.xs.XSWildcard
import java.io.File

/*
 * Walks every element reachable from the ten openTRANS document roots and
 * counts the XSD constructs the generator has to handle. Output is a plain
 * report; it drives the mapping rules in PLAN.md.
 */

private const val OT = "http://www.opentrans.org/XMLSchema/2.1"
private val ROOTS = listOf(
    "RFQ", "QUOTATION", "ORDER", "ORDERCHANGE", "ORDERRESPONSE", "DISPATCHNOTIFICATION",
    "RECEIPTACKNOWLEDGEMENT", "INVOICE", "INVOICELIST", "REMITTANCEADVICE",
)

fun main(args: Array<String>) {
    val model = SchemaLoader.load(File(args.single()))
    val stats = Stats()
    val seenElements = mutableSetOf<String>()
    val seenTypes = mutableSetOf<String>()

    fun key(e: XSElementDeclaration) = "{${e.namespace}}${e.name}#${e.typeDefinition.name ?: "anon@" + System.identityHashCode(e.typeDefinition)}"

    fun visitType(t: XSTypeDefinition, owner: String) {
        val typeKey = t.name?.let { "{${t.namespace}}$it" } ?: "anon:$owner"
        if (!seenTypes.add(typeKey)) return
        if (t is XSSimpleTypeDefinition) {
            stats.simpleType(t)
            return
        }
        t as XSComplexTypeDefinition
        if (t.name == "anyType") return
        stats.complexType(t, owner)
        val particle = t.particle ?: return
        fun walk(p: XSParticle, depth: Int, inChoice: Boolean) {
            val term: XSTerm = p.term
            when (term) {
                is XSElementDeclaration -> {
                    stats.elementUse(p, inChoice)
                    if (seenElements.add(key(term))) {
                        stats.element(term)
                        visitType(term.typeDefinition, term.name)
                    }
                }
                is XSModelGroup -> {
                    stats.group(term, p, depth, inChoice)
                    val parts = term.particles
                    for (i in 0 until parts.length) walk(parts.item(i) as XSParticle, depth + 1, inChoice || term.compositor == XSModelGroup.COMPOSITOR_CHOICE)
                }
                is XSWildcard -> stats.wildcards += owner
            }
        }
        walk(particle, 0, false)
    }

    for (root in ROOTS) {
        val e = model.getElementDeclaration(root, OT) ?: error("root $root missing")
        seenElements += key(e)
        stats.element(e)
        visitType(e.typeDefinition, root)
    }
    stats.print(seenElements.size)
}

private class Stats {
    var elements = 0
    var simpleElements = 0
    var complexByContent = mutableMapOf<String, Int>()
    var namedComplexTypes = mutableSetOf<String>()
    var anonymousComplexTypes = 0
    var attributes = 0
    var requiredAttributes = 0
    var choices = 0
    var nestedChoices = 0
    var choicesWithRepeatedAlternative = 0
    var choicesWithSequenceAlternative = 0
    var optionalChoices = 0
    var repeatedGroups = 0
    var sequencesInSequence = 0
    var listUses = 0
    var optionalUses = 0
    var requiredUses = 0
    var wrappers = mutableSetOf<String>()
    var enumerations = mutableMapOf<String, Int>()
    var patterns = mutableSetOf<String>()
    var simpleBases = mutableMapOf<String, Int>()
    val wildcards = mutableSetOf<String>()
    val anyTypeElements = mutableSetOf<String>()
    var mixed = mutableSetOf<String>()

    fun element(e: XSElementDeclaration) {
        elements++
        val t = e.typeDefinition
        if (t is XSSimpleTypeDefinition) simpleElements++
        if (t.name == "anyType" || (t is XSComplexTypeDefinition && t.baseType?.name == "anyType" && t.particle?.term is XSWildcard)) anyTypeElements += e.name
    }

    fun elementUse(p: XSParticle, inChoice: Boolean) {
        when {
            p.maxOccursUnbounded || p.maxOccurs > 1 -> listUses++
            p.minOccurs == 0 -> optionalUses++
            else -> requiredUses++
        }
    }

    fun group(g: XSModelGroup, p: XSParticle, depth: Int, inChoice: Boolean) {
        if (p.maxOccursUnbounded || p.maxOccurs > 1) repeatedGroups++
        if (g.compositor == XSModelGroup.COMPOSITOR_SEQUENCE && depth > 0 && !inChoice) sequencesInSequence++
        if (g.compositor != XSModelGroup.COMPOSITOR_CHOICE) return
        choices++
        if (depth > 1 || inChoice) nestedChoices++
        if (p.minOccurs == 0) optionalChoices++
        val parts = g.particles
        for (i in 0 until parts.length) {
            val alt = parts.item(i) as XSParticle
            if (alt.maxOccursUnbounded || alt.maxOccurs > 1) choicesWithRepeatedAlternative++
            if (alt.term is XSModelGroup) choicesWithSequenceAlternative++
        }
    }

    fun complexType(t: XSComplexTypeDefinition, owner: String) {
        val content = when (t.contentType) {
            CONTENTTYPE_EMPTY -> "empty"
            CONTENTTYPE_SIMPLE -> "simple"
            CONTENTTYPE_ELEMENT -> "element"
            CONTENTTYPE_MIXED -> "mixed"
            else -> "?"
        }
        complexByContent.merge(content, 1, Int::plus)
        if (t.contentType == CONTENTTYPE_MIXED) mixed += owner
        if (t.name != null) namedComplexTypes += t.name else anonymousComplexTypes++
        val uses = t.attributeUses
        attributes += uses.length
        for (i in 0 until uses.length) if ((uses.item(i) as org.apache.xerces.xs.XSAttributeUse).required) requiredAttributes++
        val particle = t.particle
        if (particle != null && t.attributeUses.length == 0) {
            val g = particle.term as? XSModelGroup
            if (g != null && g.particles.length == 1) {
                val only = g.particles.item(0) as XSParticle
                if (only.term is XSElementDeclaration && (only.maxOccursUnbounded || only.maxOccurs > 1)) wrappers += owner
            }
        }
    }

    fun simpleType(t: XSSimpleTypeDefinition) {
        val enums = t.lexicalEnumeration
        if (enums.length > 0) enumerations[t.name ?: "anonymous"] = enums.length
        val pats = t.lexicalPattern
        for (i in 0 until pats.length) patterns += pats.item(i)
        var base: XSTypeDefinition? = t
        while (base != null && base.namespace != "http://www.w3.org/2001/XMLSchema") base = base.baseType
        simpleBases.merge(base?.name ?: "?", 1, Int::plus)
    }

    fun print(distinctElements: Int) {
        println("reachable element declarations: $distinctElements")
        println("  with simple type: $simpleElements")
        println("complex types by content: $complexByContent")
        println("  named complex types: ${namedComplexTypes.size} ${namedComplexTypes.sorted()}")
        println("  anonymous complex types: $anonymousComplexTypes")
        println("attributes: $attributes (required: $requiredAttributes)")
        println("element uses: required=$requiredUses optional=$optionalUses repeated=$listUses")
        println("choices: $choices (nested: $nestedChoices, optional: $optionalChoices, " +
            "repeated alternative: $choicesWithRepeatedAlternative, sequence alternative: $choicesWithSequenceAlternative)")
        println("repeated groups (sequence/choice with maxOccurs>1): $repeatedGroups")
        println("sequences nested in sequences: $sequencesInSequence")
        println("wrapper elements (one repeated child, no attributes): ${wrappers.size}")
        println("anyType elements: ${anyTypeElements.sorted()}")
        println("wildcards (xsd:any) in: ${wildcards.sorted()}")
        println("mixed content in: ${mixed.sorted()}")
        println("simple types by primitive base: $simpleBases")
        println("enumerations: ${enumerations.size} types, largest: ${enumerations.entries.sortedByDescending { it.value }.take(8)}")
        println("distinct patterns: ${patterns.size}")
        patterns.sortedBy { it.length }.take(15).forEach { println("    $it") }
    }
}
