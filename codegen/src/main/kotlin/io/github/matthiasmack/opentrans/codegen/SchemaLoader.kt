package io.github.matthiasmack.opentrans.codegen

import org.apache.xerces.dom.DOMInputImpl
import org.apache.xerces.impl.xs.XMLSchemaLoader
import org.apache.xerces.xs.XSModel
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import java.io.File

/**
 * Loads the openTRANS schema into Xerces' XSModel, which resolves element
 * references, imports and type derivation for us.
 *
 * Nothing is loaded from the network: imports come from the schema directory,
 * and the W3C DTD referenced by xmldsig-core-schema.xsd (whose entities are
 * unused) is answered with an empty document.
 */
object SchemaLoader {
    fun load(rootXsd: File): XSModel {
        val dir = rootXsd.absoluteFile.parentFile
        val loader = XMLSchemaLoader()
        loader.setParameter("resource-resolver", Resolver(dir))
        return loader.loadURI(rootXsd.toURI().toString())
            ?: error("Could not load schema $rootXsd")
    }

    private class Resolver(
        private val dir: File,
    ) : LSResourceResolver {
        override fun resolveResource(
            type: String?,
            namespaceURI: String?,
            publicId: String?,
            systemId: String?,
            baseURI: String?,
        ): LSInput {
            val local = systemId?.substringAfterLast('/')?.let { File(dir, it) }
            return when {
                local != null && local.isFile -> DOMInputImpl(publicId, local.toURI().toString(), baseURI, local.inputStream(), null)
                type == "http://www.w3.org/TR/REC-xml" -> DOMInputImpl(publicId, systemId, baseURI, "", null)
                else -> error("Refusing to load external resource: $systemId")
            }
        }
    }
}
