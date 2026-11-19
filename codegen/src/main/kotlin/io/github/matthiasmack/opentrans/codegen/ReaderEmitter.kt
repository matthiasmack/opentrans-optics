package io.github.matthiasmack.opentrans.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import java.io.File
import javax.xml.namespace.QName

/**
 * Emits the XML reader for an [Ir]: one `XmlIn.readX()` function per class
 * that is read from an element, plus `readDocument()` for the ten roots.
 *
 * The generated code has the shape of the spike's hand-written reader:
 * locals per property, a `when` over child element names, then the
 * constructor call. Input is validated against the XSD beforehand, so
 * required properties are guaranteed to be set (`!!`).
 */
class ReaderEmitter(private val pkg: String, private val modelPkg: String) {
    private val qnameClass = ClassName("javax.xml.namespace", "QName")
    private val xmlIn = ClassName("io.github.matthiasmack.opentrans.xml", "XmlIn")
    private val dateTime = ClassName("io.github.matthiasmack.opentrans.model", "DateTime")
    private val bigDecimal = ClassName("java.math", "BigDecimal")
    private val model = ModelEmitter(modelPkg)
    private val constants = linkedMapOf<QName, String>()

    fun emit(ir: Ir, outDir: File) {
        val readable = readableClasses(ir)
        val file = FileSpec.builder(pkg, "GeneratedReader")
            .addFileComment("openTRANS 2.1 reader. Generated from the XSD, do not edit.")
            .addFunction(documentReader(ir))
        for (cls in ir.classes.filter { it in readable }) file.addFunction(classReader(cls))
        for ((qname, name) in constants) {
            file.addProperty(
                PropertySpec.builder(name, qnameClass, KModifier.PRIVATE)
                    .initializer("%T(%S, %S)", qnameClass, qname.namespaceURI, qname.localPart)
                    .build(),
            )
        }
        file.build().writeTo(outDir)
    }

    /** Classes that are the content of an element: roots and every class reached through an element binding. */
    private fun readableClasses(ir: Ir): Set<ClassDef> {
        val result = linkedSetOf<ClassDef>()
        result += ir.roots
        fun visitProps(props: List<Prop>) {
            for (prop in props) {
                if (prop.binding is Binding.Element && prop.type is KType.Class) result += prop.type.def
            }
        }
        ir.classes.forEach { visitProps(it.props) }
        ir.choices.flatMap { it.variants }.filter { it.kind is VariantKind.Direct }.forEach { result += it.cls }
        return result
    }

    private fun documentReader(ir: Ir): FunSpec {
        val code = CodeBlock.builder().beginControlFlow("return when (name)")
        for (root in ir.roots) code.addStatement("%N -> read${root.name}()", constant(root.element!!))
        code.addStatement("else -> throw %T(%P)", IllegalArgumentException::class, "Not an openTRANS document: \${name.localPart}")
        code.endControlFlow()
        return FunSpec.builder("readDocument")
            .addModifiers(KModifier.INTERNAL)
            .receiver(xmlIn)
            .returns(ClassName(modelPkg, DOCUMENT_INTERFACE))
            .addKdoc("Reads any of the ten openTRANS document types, depending on the current element.")
            .addCode(code.build())
            .build()
    }

    private fun classReader(cls: ClassDef): FunSpec {
        val type = ClassName(modelPkg, cls.name)
        val fn = FunSpec.builder("read${cls.name}")
            .addModifiers(KModifier.INTERNAL)
            .receiver(xmlIn)
            .returns(type)
        val body = CodeBlock.builder()
        val dispatch = CodeBlock.builder()
        val seen = mutableSetOf<QName>()
        val finals = mutableListOf<CodeBlock>()

        fun branch(qname: QName, action: CodeBlock) {
            check(seen.add(qname)) { "${cls.origin}: element ${qname.localPart} is mapped twice" }
            dispatch.add("%N -> ", constant(qname)).add(action).add("\n")
        }

        // Attributes first: reading text or children moves the cursor off the start tag.
        for (prop in cls.props.sortedBy { it.binding !is Binding.Attribute }) {
            val local = "v_${prop.name}"
            when (val binding = prop.binding) {
                is Binding.Attribute -> {
                    val read = if (prop.card == Card.Required) "requiredAttr(%S)" else "attr(%S)"
                    val conv = fromString(prop.type, "it")
                    body.addStatement("val %N = $read${if (prop.card == Card.Required) ".let { %L }" else "?.let { %L }"}", local, binding.name, conv)
                    finals += CodeBlock.of("%N = %N", prop.name, local)
                }
                Binding.Text -> {
                    body.addStatement("val %N = text().let { %L }", local, fromString(prop.type, "it"))
                    finals += CodeBlock.of("%N = %N", prop.name, local)
                }
                is Binding.Element -> {
                    val valueType = model.typeName(prop.type)
                    val read = readValue(prop.type)
                    if (prop.card is Card.Many) {
                        body.addStatement("val %N = mutableListOf<%T>()", local, valueType)
                        if (binding.wrapper != null) {
                            branch(binding.wrapper, CodeBlock.of("children { %N += %L }", local, read))
                        } else {
                            branch(binding.name, CodeBlock.of("%N += %L", local, read))
                        }
                        finals += CodeBlock.of("%N = %N", prop.name, local)
                    } else {
                        body.addStatement("var %N: %T = null", local, valueType.copy(nullable = true))
                        branch(binding.name, CodeBlock.of("%N = %L", local, read))
                        finals += CodeBlock.of("%N = %N%L", prop.name, local, if (prop.card == Card.Required) "!!" else "")
                    }
                }
                Binding.Choice -> finals += choice(cls, prop, local, body, ::branch)
                Binding.Group -> finals += group(prop, local, body, ::branch)
            }
        }

        val hasChildren = cls.props.any { it.binding !is Binding.Attribute && it.binding != Binding.Text }
        val isText = cls.props.any { it.binding == Binding.Text }
        if (hasChildren) {
            body.addStatement("val self = name")
            body.beginControlFlow("children { child ->")
            body.beginControlFlow("when (child)")
            body.add(dispatch.build())
            body.addStatement("else -> unsupported(child, self)")
            body.endControlFlow()
            body.endControlFlow()
        } else if (!isText) {
            // Empty content: still consume the element.
            body.addStatement("children { child -> unsupported(child, name) }")
        }

        if (cls.props.isEmpty()) {
            body.addStatement("return %T", type)
        } else {
            body.add("return %T(\n", type).indent()
            finals.forEach { body.add("%L,\n", it) }
            body.unindent().add(")\n")
        }
        return fn.addCode(body.build()).build()
    }

    /** Locals and branches for an xsd:choice; returns the constructor argument. */
    private fun choice(
        owner: ClassDef, prop: Prop, local: String, body: CodeBlock.Builder,
        branch: (QName, CodeBlock) -> Unit,
    ): CodeBlock {
        val choice = (prop.type as KType.Choice).def
        val choiceType = ClassName(modelPkg, choice.name)
        check(prop.card !is Card.Many) { "${owner.origin}: repeated choice ${choice.origin} is not supported yet" }
        body.addStatement("var %N: %T = null", local, choiceType.copy(nullable = true))
        val fallbacks = mutableListOf<CodeBlock>()

        choice.variants.forEachIndexed { index, variant ->
            val variantType = ClassName(modelPkg, variant.cls.name)
            when (val kind = variant.kind) {
                is VariantKind.Direct -> branch(kind.element, CodeBlock.of("%N = read%L()", local, variant.cls.name))
                is VariantKind.Wrapped -> {
                    val inner = variant.cls.props.single()
                    branch(kind.element, CodeBlock.of("%N = %T(%L)", local, variantType, readValue(inner.type)))
                }
                is VariantKind.Repeated -> {
                    val inner = variant.cls.props.single()
                    val list = "${local}_$index"
                    body.addStatement("val %N = mutableListOf<%T>()", list, model.typeName(inner.type))
                    branch(kind.element, CodeBlock.of("%N += %L", list, readValue(inner.type)))
                    fallbacks += CodeBlock.of("%N.takeIf { it.isNotEmpty() }?.let { %T(it) }", list, variantType)
                }
                VariantKind.Sequence -> {
                    val flag = "${local}_${index}_seen"
                    body.addStatement("var %N = false", flag)
                    val args = mutableListOf<CodeBlock>()
                    for (inner in variant.cls.props) {
                        val innerLocal = "${local}_${index}_${inner.name}"
                        val binding = inner.binding as Binding.Element
                        val read = readValue(inner.type)
                        if (inner.card is Card.Many) {
                            body.addStatement("val %N = mutableListOf<%T>()", innerLocal, model.typeName(inner.type))
                            branch(binding.name, CodeBlock.of("{ %N = true; %N += %L }", flag, innerLocal, read))
                            args += CodeBlock.of("%N = %N", inner.name, innerLocal)
                        } else {
                            body.addStatement("var %N: %T = null", innerLocal, model.typeName(inner.type).copy(nullable = true))
                            branch(binding.name, CodeBlock.of("{ %N = true; %N = %L }", flag, innerLocal, read))
                            args += CodeBlock.of("%N = %N%L", inner.name, innerLocal, if (inner.card == Card.Required) "!!" else "")
                        }
                    }
                    fallbacks += CodeBlock.of("(if (%N) %T(%L) else null)", flag, variantType, args.joinToCode())
                }
            }
        }
        val value = CodeBlock.builder().add("%N", local)
        fallbacks.forEach { value.add(" ?: %L", it) }
        if (prop.card == Card.Required) value.add(" ?: error(%S)", "${owner.origin}: no alternative of ${choice.origin} present")
        return CodeBlock.of("%N = %L", prop.name, value.build())
    }

    /** Locals and branches for a repeated xsd:sequence; returns the constructor argument. */
    private fun group(prop: Prop, local: String, body: CodeBlock.Builder, branch: (QName, CodeBlock) -> Unit): CodeBlock {
        val entry = (prop.type as KType.Class).def
        val entryType = ClassName(modelPkg, entry.name)
        body.addStatement("val %N = mutableListOf<%T>()", local, entryType)
        body.addStatement("var %N = false", "${local}_open")
        val args = mutableListOf<CodeBlock>()
        val resets = CodeBlock.builder()
        entry.props.forEachIndexed { index, inner ->
            val innerLocal = "${local}_${inner.name}"
            val binding = inner.binding as Binding.Element
            val read = readValue(inner.type)
            val start = if (index == 0) CodeBlock.of("flush_%L(); %N = true; ", local, "${local}_open") else CodeBlock.of("")
            if (inner.card is Card.Many) {
                body.addStatement("val %N = mutableListOf<%T>()", innerLocal, model.typeName(inner.type))
                branch(binding.name, CodeBlock.of("{ %L%N += %L }", start, innerLocal, read))
                args += CodeBlock.of("%N = %N.toList()", inner.name, innerLocal)
                resets.addStatement("%N.clear()", innerLocal)
            } else {
                body.addStatement("var %N: %T = null", innerLocal, model.typeName(inner.type).copy(nullable = true))
                branch(binding.name, CodeBlock.of("{ %L%N = %L }", start, innerLocal, read))
                args += CodeBlock.of("%N = %N%L", inner.name, innerLocal, if (inner.card == Card.Required) "!!" else "")
                resets.addStatement("%N = null", innerLocal)
            }
        }
        body.beginControlFlow("fun flush_%L()", local)
        body.addStatement("if (!%N) return", "${local}_open")
        body.addStatement("%N += %T(%L)", local, entryType, args.joinToCode())
        body.add(resets.build())
        body.endControlFlow()
        return CodeBlock.of("%N = %N.also { flush_%L() }", prop.name, local, local)
    }

    /** Expression reading the current element as [type]. */
    private fun readValue(type: KType): CodeBlock = when (type) {
        is KType.Scalar -> when (type.kind) {
            ScalarKind.STRING -> CodeBlock.of("text()")
            ScalarKind.DECIMAL -> CodeBlock.of("decimal()")
            ScalarKind.INT -> CodeBlock.of("int()")
            ScalarKind.BOOLEAN -> CodeBlock.of("boolean()")
            ScalarKind.DATETIME -> CodeBlock.of("dateTime()")
        }
        is KType.Enum -> CodeBlock.of("%T.fromXml(text().trim())", ClassName(modelPkg, type.def.name))
        is KType.Class -> CodeBlock.of("read%L()", type.def.name)
        KType.Raw -> CodeBlock.of("raw()")
        is KType.Choice -> error("a choice is never read as a single value")
    }

    /** Expression converting the string [v] (attribute or text content) to [type]. */
    private fun fromString(type: KType, v: String): CodeBlock = when (type) {
        is KType.Scalar -> when (type.kind) {
            ScalarKind.STRING -> CodeBlock.of("%L", v)
            ScalarKind.DECIMAL -> CodeBlock.of("%T(%L.trim())", bigDecimal, v)
            ScalarKind.INT -> CodeBlock.of("%L.trim().toInt()", v)
            ScalarKind.BOOLEAN -> CodeBlock.of("%L.trim().equals(%S, ignoreCase = true)", v, "true")
            ScalarKind.DATETIME -> CodeBlock.of("%T(%L.trim())", dateTime, v)
        }
        is KType.Enum -> CodeBlock.of("%T.fromXml(%L.trim())", ClassName(modelPkg, type.def.name), v)
        else -> error("$type cannot be read from a string")
    }

    private fun constant(qname: QName): String = constants.getOrPut(qname) {
        val prefix = when (qname.namespaceURI) {
            OT_NS -> "OT"
            BMECAT_NS -> "BMECAT"
            else -> "NS"
        }
        "${prefix}_" + qname.localPart.uppercase().replace(Regex("[^A-Z0-9]"), "_")
    }

    private fun List<CodeBlock>.joinToCode(): CodeBlock = CodeBlock.builder().apply {
        this@joinToCode.forEachIndexed { i, block -> if (i > 0) add(", "); add(block) }
    }.build()
}
