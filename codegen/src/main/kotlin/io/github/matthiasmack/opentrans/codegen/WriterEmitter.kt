package io.github.matthiasmack.opentrans.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.STRING
import java.io.File
import javax.xml.namespace.QName

/**
 * Emits the XML writer for an [Ir]: one `XmlOut.writeX(tag, value)` function
 * per class written as element content, plus `writeDocument()` for the roots.
 *
 * Elements are written in the order of the [ClassDef] properties, which is
 * the xsd:sequence order. The element name is passed in because one class can
 * be the content of several elements (e.g. `PartyId` for PARTY_ID,
 * BUYER_IDREF, SUPPLIER_IDREF, ...).
 */
class WriterEmitter(private val pkg: String, private val modelPkg: String) {
    private val xmlOut = ClassName("io.github.matthiasmack.opentrans.xml", "XmlOut")

    fun emit(ir: Ir, outDir: File) {
        val file = FileSpec.builder(pkg, "GeneratedWriter")
            .addFileComment("openTRANS 2.1 writer. Generated from the XSD, do not edit.")
            .addFunction(documentWriter(ir))
        for (cls in ir.classes.filter { it in writtenClasses(ir) }) file.addFunction(classWriter(cls))
        file.build().writeTo(outDir)
    }

    private fun writtenClasses(ir: Ir): Set<ClassDef> {
        val result = linkedSetOf<ClassDef>()
        result += ir.roots
        ir.classes.flatMap { it.props }.filter { it.binding is Binding.Element && it.type is KType.Class }
            .forEach { result += (it.type as KType.Class).def }
        ir.choices.flatMap { it.variants }.filter { it.kind is VariantKind.Direct }.forEach { result += it.cls }
        return result
    }

    private fun documentWriter(ir: Ir): FunSpec {
        val code = CodeBlock.builder().beginControlFlow("return %T().document", xmlOut).beginControlFlow("when (document)")
        for (root in ir.roots) {
            code.addStatement("is %T -> write%L(%S, document)", ClassName(modelPkg, root.name), root.name, tag(root.element!!))
        }
        code.endControlFlow().endControlFlow()
        return FunSpec.builder("writeDocument")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("document", ClassName(modelPkg, DOCUMENT_INTERFACE))
            .returns(STRING)
            .addKdoc("Writes any openTRANS document as XML text (not yet validated).")
            .addCode(code.build())
            .build()
    }

    private fun classWriter(cls: ClassDef): FunSpec {
        val fn = FunSpec.builder("write${cls.name}")
            .addModifiers(KModifier.INTERNAL)
            .receiver(xmlOut)
            .addParameter("tag", STRING)
            .addParameter("value", ClassName(modelPkg, cls.name))

        val attributes = cls.fixedAttributes.map { (name, fixed) -> CodeBlock.of("%S to %S", name, fixed) } +
            cls.props.filter { it.binding is Binding.Attribute }.flatMap { prop ->
                val binding = prop.binding as Binding.Attribute
                val text = toText(prop.type, "value.${escapeName(prop.name)}", nullable = prop.card != Card.Required)
                if (binding.namespace == null) {
                    listOf(CodeBlock.of("%S to %L", binding.name, text))
                } else {
                    // Qualified attribute (xmime:contentType): declare its namespace on the element itself.
                    val prefix = ATTRIBUTE_PREFIXES[binding.namespace] ?: error("no prefix for attribute namespace ${binding.namespace}")
                    listOf(
                        CodeBlock.of("%S to (%L)?.let { %S }", "xmlns:$prefix", text, binding.namespace),
                        CodeBlock.of("%S to %L", "$prefix:${binding.name}", text),
                    )
                }
            }
        val attributeArgs = attributes.fold(CodeBlock.builder()) { b, a -> b.add(", ").add(a) }.build()

        val textProp = cls.props.firstOrNull { it.binding == Binding.Text }
        val content = cls.props.filter { it.binding !is Binding.Attribute && it.binding != Binding.Text }
        val body = CodeBlock.builder()
        when {
            textProp != null ->
                body.addStatement("text(tag, %L%L)", toText(textProp.type, "value.${escapeName(textProp.name)}", nullable = false), attributeArgs)
            content.isEmpty() -> body.addStatement("empty(tag%L)", attributeArgs)
            else -> {
                body.beginControlFlow("element(tag%L)", attributeArgs)
                content.forEach { writeProp(it, "value", body) }
                body.endControlFlow()
            }
        }
        return fn.addCode(body.build()).build()
    }

    private fun writeProp(prop: Prop, owner: String, body: CodeBlock.Builder) {
        val access = "$owner.${escapeName(prop.name)}"
        when (val binding = prop.binding) {
            is Binding.Element -> {
                val tag = tag(binding.name)
                when {
                    prop.card is Card.Many && binding.wrapper != null -> {
                        body.beginControlFlow("if (%L.isNotEmpty()) element(%S)", access, tag(binding.wrapper))
                        body.addStatement("%L.forEach { %L }", access, writeValue(prop.type, tag, "it"))
                        body.endControlFlow()
                    }
                    prop.card is Card.Many -> body.addStatement("%L.forEach { %L }", access, writeValue(prop.type, tag, "it"))
                    prop.card == Card.Optional -> body.addStatement("%L?.let { %L }", access, writeValue(prop.type, tag, "it"))
                    else -> body.addStatement("%L", writeValue(prop.type, tag, access))
                }
            }
            Binding.Choice -> {
                val choice = (prop.type as KType.Choice).def
                body.beginControlFlow("when (val choice = %L)", access)
                for (variant in choice.variants) {
                    val variantType = ClassName(modelPkg, variant.cls.name)
                    when (val kind = variant.kind) {
                        is VariantKind.Direct -> body.addStatement("is %T -> write%L(%S, choice)", variantType, variant.cls.name, tag(kind.element))
                        is VariantKind.Wrapped -> {
                            val inner = variant.cls.props.single()
                            body.addStatement("is %T -> %L", variantType, writeValue(inner.type, tag(kind.element), "choice.${escapeName(inner.name)}"))
                        }
                        is VariantKind.Repeated -> {
                            val inner = variant.cls.props.single()
                            body.addStatement("is %T -> choice.%L.forEach { %L }", variantType, escapeName(inner.name), writeValue(inner.type, tag(kind.element), "it"))
                        }
                        VariantKind.Sequence -> {
                            body.beginControlFlow("is %T ->", variantType)
                            variant.cls.props.forEach { writeProp(it, "choice", body) }
                            body.endControlFlow()
                        }
                    }
                }
                if (prop.card == Card.Optional) body.addStatement("null -> Unit")
                body.endControlFlow()
            }
            Binding.Group -> {
                val entry = (prop.type as KType.Class).def
                body.beginControlFlow("%L.forEach { entry ->", access)
                entry.props.forEach { writeProp(it, "entry", body) }
                body.endControlFlow()
            }
            is Binding.Attribute, Binding.Text -> error("handled by the caller")
        }
    }

    /** Statement writing [expr] of [type] as element [tag]. */
    private fun writeValue(type: KType, tag: String, expr: String): CodeBlock = when (type) {
        is KType.Class -> CodeBlock.of("write%L(%S, %L)", type.def.name, tag, expr)
        KType.Raw -> CodeBlock.of("raw(%S, %L)", tag, expr)
        else -> CodeBlock.of("text(%S, %L)", tag, toText(type, expr, nullable = false))
    }

    /** Expression turning [expr] of [type] into its XML text. */
    private fun toText(type: KType, expr: String, nullable: Boolean): CodeBlock {
        val q = if (nullable) "?." else "."
        return when (type) {
            is KType.Scalar -> when (type.kind) {
                ScalarKind.STRING -> CodeBlock.of("%L", expr)
                ScalarKind.DECIMAL -> CodeBlock.of("%L${q}toPlainString()", expr)
                ScalarKind.INT, ScalarKind.BOOLEAN -> CodeBlock.of("%L${q}toString()", expr)
                ScalarKind.DATETIME -> CodeBlock.of("%L${q}value", expr)
            }
            is KType.Enum -> CodeBlock.of("%L${q}xml", expr)
            else -> error("$type has no text form")
        }
    }

    private fun tag(qname: QName): String = when (qname.namespaceURI) {
        OT_NS -> qname.localPart
        BMECAT_NS -> "bmecat:${qname.localPart}"
        else -> error("no prefix for ${qname.namespaceURI}")
    }

    /** Property names that are Kotlin keywords need backticks in plain code strings. */
    private fun escapeName(name: String) = if (name in KEYWORDS) "`$name`" else name

    private companion object {
        val ATTRIBUTE_PREFIXES = mapOf("http://www.w3.org/2005/05/xmlmime" to "xmime")

        val KEYWORDS = setOf("package", "in", "is", "as", "class", "object", "fun", "val", "var", "when", "if", "else", "for", "while", "do", "return", "true", "false", "null", "this", "super", "throw", "try", "typealias", "typeof", "interface")
    }
}
