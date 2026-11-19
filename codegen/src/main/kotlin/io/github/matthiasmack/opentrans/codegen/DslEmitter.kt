package io.github.matthiasmack.opentrans.codegen

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MUTABLE_LIST
import com.squareup.kotlinpoet.NUMBER
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import java.io.File

/**
 * Emits a type-safe builder DSL for every class of the [Ir]:
 *
 * ```
 * val item = OrderItem {
 *     lineItemId = "1"
 *     productId { supplierPid("SKU-A") }
 *     quantity(3)
 *     orderUnit = "C62"
 *     shortDescription("Screw", lang = "eng")   // list adder
 * }
 * ```
 *
 * Per property: a `var` (or a mutable list), plus helpers: a nested block for
 * classes, a parameter-list shortcut for small value classes (MlString,
 * PartyId, ...), conversions for dates and decimals, and one helper per
 * alternative of a choice. `build()` checks required properties and names the
 * XML element in the error.
 */
class DslEmitter(private val pkg: String, private val modelPkg: String) {
    private val model = ModelEmitter(modelPkg)
    private val dslMarker = ClassName(pkg, "OpenTransDsl")
    private val rawXml = ClassName("io.github.matthiasmack.opentrans.model", "XmlContent")
    private val bigDecimal = ClassName("java.math", "BigDecimal")
    private val contentBuilder = ClassName(pkg, "XmlContentBuilder")
    private val incomplete = ClassName(modelPkg, "IncompleteDocumentException")

    fun emit(ir: Ir, outDir: File) {
        val file = FileSpec.builder(pkg, "GeneratedBuilders")
            .addFileComment("openTRANS 2.1 builder DSL. Generated from the XSD, do not edit.")
        for (cls in ir.classes.filter { it.props.isNotEmpty() }) {
            file.addType(builder(cls))
            file.addFunction(factory(cls))
        }
        file.build().writeTo(outDir)
    }

    private fun builderName(cls: ClassDef) = ClassName(pkg, "${cls.name}Builder")

    private fun factory(cls: ClassDef): FunSpec {
        val type = ClassName(modelPkg, cls.name)
        return FunSpec.builder("invoke")
            .addModifiers(KModifier.OPERATOR)
            .receiver(type.nestedClass("Companion"))
            .addParameter("block", LambdaTypeName.get(receiver = builderName(cls), returnType = UNIT))
            .returns(type)
            .addKdoc("Builds a [%T] with the DSL: `%L { ... }`.", type, cls.name)
            .addStatement("return %T().apply(block).build()", builderName(cls))
            .build()
    }

    /** A class whose properties are all single scalars or enums gets a parameter-list shortcut. */
    private fun isSimple(cls: ClassDef) = cls.props.size <= 6 &&
        cls.props.all { (it.type is KType.Scalar || it.type is KType.Enum) && it.card !is Card.Many }

    private fun builder(cls: ClassDef): TypeSpec {
        val type = TypeSpec.classBuilder(builderName(cls))
            .addAnnotation(dslMarker)
            .addKdoc("Builder for [%T] (openTRANS `%L`).", ClassName(modelPkg, cls.name), cls.origin)
        val members = NameRegistry(cls.props.map { it.name }.toSet())
        val args = mutableListOf<CodeBlock>()

        for (prop in cls.props) {
            val valueType = model.typeName(prop.type)
            if (prop.card is Card.Many) {
                type.addProperty(
                    PropertySpec.builder(prop.name, MUTABLE_LIST.parameterizedBy(valueType))
                        .initializer("mutableListOf()")
                        .build(),
                )
                val singular = members.claimOrNull(singularName(prop))
                if (singular != null) adders(prop, singular, valueType).forEach(type::addFunction)
                args += CodeBlock.of("%N = %N.toList()", prop.name, prop.name)
            } else {
                type.addProperty(
                    PropertySpec.builder(prop.name, valueType.copy(nullable = true))
                        .mutable()
                        .initializer("null")
                        .build(),
                )
                setters(prop, valueType, members).forEach(type::addFunction)
                args += if (prop.card == Card.Required) {
                    CodeBlock.of("%N = %N ?: throw %T(%S, %S)", prop.name, prop.name, incomplete, "${cls.origin}/${xmlLabel(prop)}", "${cls.origin} needs ${xmlLabel(prop)} (${prop.name})")
                } else {
                    CodeBlock.of("%N = %N", prop.name, prop.name)
                }
            }
        }

        val build = CodeBlock.builder().add("return %T(\n", ClassName(modelPkg, cls.name)).indent()
        args.forEach { build.add("%L,\n", it) }
        build.unindent().add(")\n")
        type.addFunction(
            FunSpec.builder("build")
                .addModifiers(KModifier.INTERNAL)
                .returns(ClassName(modelPkg, cls.name))
                .addCode(build.build())
                .build(),
        )
        return type.build()
    }

    /** Helpers that set a single-valued property. */
    private fun setters(prop: Prop, valueType: com.squareup.kotlinpoet.TypeName, members: NameRegistry): List<FunSpec> {
        val name = prop.name
        return when (val t = prop.type) {
            is KType.Class -> classHelpers(name, t.def) { "this.%N = %L" }
            is KType.Scalar -> scalarHelpers(name, t.kind) { "this.%N = %L" }
            KType.Raw -> listOf(
                FunSpec.builder(name).addParameter("xml", STRING)
                    .addStatement("this.%N = %T.parse(xml)", name, rawXml).build(),
                FunSpec.builder(name)
                    .addParameter("block", LambdaTypeName.get(receiver = contentBuilder, returnType = UNIT))
                    .addStatement("this.%N = %T().apply(block).build()", name, contentBuilder).build(),
            )
            is KType.Choice -> t.def.variants.flatMap { variant ->
                val helper = members.claimOrNull(variant.cls.name.replaceFirstChar(Char::lowercase)) ?: return@flatMap emptyList()
                classHelpers(helper, variant.cls, target = name) { "this.%N = %L" }
            }
            is KType.Enum -> emptyList()
        }
    }

    /** Helpers that add to a list property. */
    private fun adders(prop: Prop, singular: String, valueType: com.squareup.kotlinpoet.TypeName): List<FunSpec> = when (val t = prop.type) {
        is KType.Class -> classHelpers(singular, t.def, target = prop.name) { "this.%N += %L" }
        is KType.Scalar -> scalarHelpers(singular, t.kind, target = prop.name) { "this.%N += %L" }
        else -> listOf(
            FunSpec.builder(singular).addParameter("value", valueType)
                .addStatement("this.%N += value", prop.name).build(),
        )
    }

    /**
     * For a class value: always a nested builder block, and for simple classes
     * additionally a parameter-list shortcut (`name("Example", lang = "eng")`).
     */
    private fun classHelpers(name: String, cls: ClassDef, target: String = name, op: () -> String): List<FunSpec> {
        val clsType = ClassName(modelPkg, cls.name)
        val block = FunSpec.builder(name)
            .addParameter("block", LambdaTypeName.get(receiver = builderName(cls), returnType = UNIT))
            .addStatement(op(), target, CodeBlock.of("%T().apply(block).build()", builderName(cls)))
            .build()
        if (!isSimple(cls)) return listOf(block)

        val shortcut = FunSpec.builder(name)
        val call = CodeBlock.builder().add("%T(", clsType)
        cls.props.forEachIndexed { i, p ->
            val param = ParameterSpec.builder(p.name, model.propertyType(p))
            if (p.card == Card.Optional) param.defaultValue("null")
            shortcut.addParameter(param.build())
            if (i > 0) call.add(", ")
            call.add("%N = %N", p.name, p.name)
        }
        call.add(")")
        return listOf(block, shortcut.addStatement(op(), target, call.build()).build())
    }

    /** Conversions for dates and decimals: `orderDate("2026-11-17")`, `quantity(2.5)`. */
    private fun scalarHelpers(name: String, kind: ScalarKind, target: String = name, op: () -> String): List<FunSpec> = when (kind) {
        ScalarKind.DATETIME -> listOf(
            FunSpec.builder(name).addKdoc("Anything date-like: DateTime, LocalDate, OffsetDateTime, YearMonth or an XSD date string.")
                .addParameter("value", ANY).addStatement(op(), target, CodeBlock.of("value.toDateTime()")).build(),
        )
        ScalarKind.DECIMAL -> listOf(
            FunSpec.builder(name).addParameter("value", NUMBER).addStatement(op(), target, CodeBlock.of("value.toDecimal()")).build(),
            FunSpec.builder(name).addParameter("value", STRING).addStatement(op(), target, CodeBlock.of("%T(value)", bigDecimal)).build(),
        )
        else -> if (target == name) emptyList() else listOf(
            FunSpec.builder(name).addParameter("value", model.typeName(KType.Scalar(kind)))
                .addStatement(op(), target, CodeBlock.of("value")).build(),
        )
    }

    private fun singularName(prop: Prop): String = when (val b = prop.binding) {
        is Binding.Element -> Names.propertyName(b.name.localPart)
        else -> (prop.type as? KType.Class)?.def?.name?.replaceFirstChar(Char::lowercase) ?: prop.name
    }.let { if (it == prop.name) (prop.type as? KType.Class)?.def?.name?.replaceFirstChar(Char::lowercase) ?: it else it }

    private fun xmlLabel(prop: Prop): String = when (val b = prop.binding) {
        is Binding.Element -> b.name.localPart
        is Binding.Attribute -> "@${b.name}"
        else -> prop.name
    }
}

/** Like [NameRegistry.claim], but gives up instead of numbering: helpers are optional. */
fun NameRegistry.claimOrNull(preferred: String): String? = claim(preferred).takeIf { it == preferred }
