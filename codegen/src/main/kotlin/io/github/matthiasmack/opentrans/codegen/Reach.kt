package io.github.matthiasmack.opentrans.codegen

import org.apache.xerces.xs.XSComplexTypeDefinition
import org.apache.xerces.xs.XSConstants
import org.apache.xerces.xs.XSElementDeclaration
import org.apache.xerces.xs.XSModelGroup
import org.apache.xerces.xs.XSParticle
import java.io.File

/** Which BMEcat elements are reachable from the openTRANS roots, and which name clashes remain. */
fun main(args: Array<String>) {
    val model = SchemaLoader.load(File(args.single()))
    val ot = "http://www.opentrans.org/XMLSchema/2.1"
    val roots = listOf("RFQ", "QUOTATION", "ORDER", "ORDERCHANGE", "ORDERRESPONSE", "DISPATCHNOTIFICATION",
        "RECEIPTACKNOWLEDGEMENT", "INVOICE", "INVOICELIST", "REMITTANCEADVICE")
    val reached = mutableSetOf<Pair<String, String>>()
    fun visit(e: XSElementDeclaration) {
        if (!reached.add(e.namespace to e.name)) return
        val t = e.typeDefinition as? XSComplexTypeDefinition ?: return
        fun walk(p: XSParticle) {
            when (val term = p.term) {
                is XSElementDeclaration -> visit(term)
                is XSModelGroup -> for (j in 0 until term.particles.length) walk(term.particles.item(j) as XSParticle)
            }
        }
        t.particle?.let(::walk)
    }
    roots.forEach { visit(model.getElementDeclaration(it, ot)) }

    val all = model.getComponents(XSConstants.ELEMENT_DECLARATION)
    val byNs = (0 until all.length).map { all.item(it) as XSElementDeclaration }.groupBy { it.namespace.substringAfterLast('/') }
    for ((ns, els) in byNs) {
        val r = reached.count { it.first.substringAfterLast('/') == ns }
        println("$ns: ${els.size} global elements, $r reachable from openTRANS roots")
    }
    val clashes = reached.groupBy { it.second }.filterValues { it.size > 1 }.keys.sorted()
    println("reachable name clashes: $clashes")
}
