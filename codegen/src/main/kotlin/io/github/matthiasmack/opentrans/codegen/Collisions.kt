package io.github.matthiasmack.opentrans.codegen

import org.apache.xerces.xs.XSComplexTypeDefinition
import org.apache.xerces.xs.XSConstants
import org.apache.xerces.xs.XSElementDeclaration
import org.apache.xerces.xs.XSModelGroup
import org.apache.xerces.xs.XSParticle
import org.apache.xerces.xs.XSSimpleTypeDefinition
import java.io.File

/** Quick check for things that complicate naming: same local name in two namespaces, local (non-global) elements, repeated groups. */
fun main(args: Array<String>) {
    val model = SchemaLoader.load(File(args.single()))
    val globals = model.getComponents(XSConstants.ELEMENT_DECLARATION)
    val byName = mutableMapOf<String, MutableSet<String>>()
    val local = mutableSetOf<String>()
    val repeatedGroups = mutableSetOf<String>()
    val anonEnums = mutableMapOf<String, Int>()
    for (i in 0 until globals.length) {
        val e = globals.item(i) as XSElementDeclaration
        byName.getOrPut(e.name) { mutableSetOf() } += e.namespace.substringAfterLast('/')
        (e.typeDefinition as? XSSimpleTypeDefinition)?.let { if (it.anonymous && it.lexicalEnumeration.length > 0) anonEnums[e.name] = it.lexicalEnumeration.length }
        val t = e.typeDefinition as? XSComplexTypeDefinition ?: continue
        fun walk(p: XSParticle) {
            when (val term = p.term) {
                is XSElementDeclaration -> if (term.scope == XSConstants.SCOPE_LOCAL) local += "${e.name}/${term.name}"
                is XSModelGroup -> {
                    if (p.maxOccursUnbounded || p.maxOccurs > 1) repeatedGroups += e.name
                    for (j in 0 until term.particles.length) walk(term.particles.item(j) as XSParticle)
                }
            }
        }
        t.particle?.let(::walk)
    }
    println("names in both namespaces: ${byName.filterValues { it.size > 1 }.keys.sorted()}")
    println("local element declarations: ${local.sorted()}")
    println("elements with repeated groups: ${repeatedGroups.sorted()}")
    println("anonymous enumerations on elements: ${anonEnums.size}: ${anonEnums.keys.sorted()}")
}
