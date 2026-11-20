package io.github.matthiasmack.opentrans.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import java.io.File
import java.math.BigDecimal

/** Emits the Kotlin model (data classes, enums, sealed choices) for an [Ir]. */
const val DOCUMENT_INTERFACE = "OpenTransDocument"

class ModelEmitter(
    private val pkg: String,
) {
    private val optics = ClassName("arrow.optics", "optics")
    private val dateTime = ClassName("io.github.matthiasmack.opentrans.model", "DateTime")
    private val rawXml = ClassName("io.github.matthiasmack.opentrans.model", "XmlContent")

    private val document = ClassName(pkg, DOCUMENT_INTERFACE)
    private var roots: Set<ClassDef> = emptySet()

    fun emit(
        ir: Ir,
        outDir: File,
    ) {
        roots = ir.roots.toSet()
        write(documentFile(ir), outDir)
        ir.classes.forEach { write(classFile(it), outDir) }
        ir.enums.forEach { write(enumFile(it), outDir) }
        ir.choices.forEach { write(choiceFile(it), outDir) }
        write(facetConstantsFile(), outDir)
    }

    // --- facet checks (G7) ---

    private val patterns = linkedMapOf<String, String>()
    private val valueSets = linkedMapOf<List<String>, String>()

    /** Emits the facet checks for [prop] into the init block. */
    private fun facetChecks(
        cls: ClassDef,
        prop: Prop,
        init: CodeBlock.Builder,
    ) {
        val facets = prop.facets ?: return
        // The checked value: the property itself, or `value` of a simple-content class (NAME -> MlString).
        val (kind, access) =
            when (val t = prop.type) {
                is KType.Scalar -> {
                    t.kind to "it"
                }

                is KType.Class -> {
                    val text = t.def.props.firstOrNull { it.binding == Binding.Text } ?: return
                    (text.type as? KType.Scalar ?: return).kind to "it.value"
                }

                else -> {
                    return
                }
            }
        val label = "${cls.origin}/${xmlLabel(prop)}"
        val checks = CodeBlock.builder()
        when (kind) {
            ScalarKind.STRING -> {
                if (facets.minLength != null || facets.maxLength != null) {
                    checks.addStatement("facetLength(%L, %L, %L, %S)", access, facets.minLength, facets.maxLength, label)
                }
                for (pattern in facets.patterns) checks.addStatement("facetPattern(%L, %N, %S)", access, patternConstant(pattern), label)
                if (facets.enumeration.isNotEmpty()) {
                    checks.addStatement(
                        "facetValues(%L, %N, %S)",
                        access,
                        valuesConstant(facets.enumeration),
                        label,
                    )
                }
            }

            ScalarKind.DECIMAL, ScalarKind.INT -> {
                val decimal = if (kind == ScalarKind.INT) "$access.toBigDecimal()" else access
                if (listOf(facets.minInclusive, facets.maxInclusive, facets.minExclusive, facets.maxExclusive).any { it != null }) {
                    checks.addStatement(
                        "facetRange(%L, %S, %S, %S, %S, %S)",
                        decimal,
                        facets.minInclusive,
                        facets.maxInclusive,
                        facets.minExclusive,
                        facets.maxExclusive,
                        label,
                    )
                }
                if (facets.totalDigits != null || facets.fractionDigits != null) {
                    checks.addStatement("facetDigits(%L, %L, %L, %S)", decimal, facets.totalDigits, facets.fractionDigits, label)
                }
            }

            // DateTime checks its own pattern; booleans and enums cannot hold invalid values.
            ScalarKind.DATETIME, ScalarKind.BOOLEAN -> {
                // nothing to check here
            }
        }
        val body = checks.build()
        if (body.isEmpty()) return
        val loop =
            when (prop.card) {
                Card.Required -> "%N.let {"
                Card.Optional -> "%N?.let {"
                is Card.Many -> "%N.forEach {"
            }
        init.beginControlFlow(loop, prop.name).add(body).endControlFlow()
    }

    private fun patternConstant(xsd: String): String = patterns.getOrPut(XsdRegex.toJava(xsd)) { "XSD_PATTERN_${patterns.size + 1}" }

    private fun valuesConstant(values: List<String>): String = valueSets.getOrPut(values) { "XSD_VALUES_${valueSets.size + 1}" }

    private fun facetConstantsFile(): FileSpec {
        val file =
            FileSpec
                .builder(
                    pkg,
                    "GeneratedFacets",
                ).addFileComment("XSD patterns and value lists used by the facet checks. Generated, do not edit.")
        for ((regex, name) in patterns) {
            file.addProperty(PropertySpec.builder(name, Regex::class, KModifier.INTERNAL).initializer("Regex(%S)", regex).build())
        }
        for ((values, name) in valueSets) {
            val init = CodeBlock.builder().add("setOf(")
            values.forEachIndexed { i, v ->
                if (i > 0) init.add(", ")
                init.add("%S", v)
            }
            init.add(")")
            file.addProperty(PropertySpec.builder(name, SET.parameterizedBy(STRING), KModifier.INTERNAL).initializer(init.build()).build())
        }
        return file.build()
    }

    private fun write(
        file: FileSpec,
        outDir: File,
    ) = file.writeTo(outDir)

    fun typeName(type: KType): TypeName =
        when (type) {
            is KType.Scalar -> {
                when (type.kind) {
                    ScalarKind.STRING -> STRING
                    ScalarKind.DECIMAL -> BigDecimal::class.asClassName()
                    ScalarKind.INT -> Int::class.asClassName()
                    ScalarKind.BOOLEAN -> Boolean::class.asClassName()
                    ScalarKind.DATETIME -> dateTime
                }
            }

            is KType.Enum -> {
                ClassName(pkg, type.def.name)
            }

            is KType.Class -> {
                ClassName(pkg, type.def.name)
            }

            is KType.Choice -> {
                ClassName(pkg, type.def.name)
            }

            KType.Raw -> {
                rawXml
            }
        }

    fun propertyType(prop: Prop): TypeName {
        val base = typeName(prop.type)
        return when (prop.card) {
            Card.Required -> base
            Card.Optional -> base.copy(nullable = true)
            is Card.Many -> LIST.parameterizedBy(base)
        }
    }

    private fun classFile(cls: ClassDef): FileSpec {
        val name = ClassName(pkg, cls.name)
        val doc = "openTRANS 2.1: `${cls.origin}`. Generated, do not edit."
        val supers = cls.implements.map { ClassName(pkg, it.name) } + listOfNotNull(document.takeIf { cls in roots })

        val type =
            if (cls.props.isEmpty()) {
                TypeSpec
                    .objectBuilder(name)
                    .addModifiers(KModifier.DATA)
                    .addKdoc(doc)
                    .addSuperinterfaces(supers)
                    .build()
            } else {
                val ctor = FunSpec.constructorBuilder()
                val builder =
                    TypeSpec
                        .classBuilder(name)
                        .addModifiers(KModifier.DATA)
                        .addAnnotation(optics)
                        .addKdoc(doc)
                        .addSuperinterfaces(supers)
                for (prop in cls.props) {
                    val propType = propertyType(prop)
                    val param = ParameterSpec.builder(prop.name, propType)
                    when (val card = prop.card) {
                        Card.Optional -> param.defaultValue("null")
                        is Card.Many -> if (card.min == 0) param.defaultValue("emptyList()")
                        Card.Required -> Unit
                    }
                    ctor.addParameter(param.build())
                    builder.addProperty(PropertySpec.builder(prop.name, propType).initializer(prop.name).build())
                }
                builder.primaryConstructor(ctor.build())
                val init = CodeBlock.builder()
                for (prop in cls.props.filter { (it.card as? Card.Many)?.min?.let { min -> min >= 1 } == true }) {
                    init.addStatement(
                        "if (%N.isEmpty()) throw IncompleteDocumentException(%S, %S)",
                        prop.name,
                        "${cls.origin}/${xmlLabel(prop)}",
                        "${cls.origin} needs at least one ${xmlLabel(prop)}",
                    )
                }
                for (prop in cls.props) facetChecks(cls, prop, init)
                if (init.isNotEmpty()) builder.addInitializerBlock(init.build())
                builder.addType(TypeSpec.companionObjectBuilder().build()).build()
            }
        return FileSpec.builder(name).addType(type).build()
    }

    private fun enumFile(def: EnumDef): FileSpec {
        val name = ClassName(pkg, def.name)
        val builder =
            TypeSpec
                .enumBuilder(name)
                .addKdoc("openTRANS 2.1: values of `${def.origin}`. Generated, do not edit.")
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("xml", STRING).build())
                .addProperty(PropertySpec.builder("xml", STRING).initializer("xml").build())
        val used = NameRegistry()
        for (value in def.values) {
            builder.addEnumConstant(
                used.claim(Names.enumConstant(value)),
                TypeSpec.anonymousClassBuilder().addSuperclassConstructorParameter("%S", value).build(),
            )
        }
        builder.addType(
            TypeSpec
                .companionObjectBuilder()
                .addFunction(
                    FunSpec
                        .builder("fromXml")
                        .addParameter("value", STRING)
                        .returns(name)
                        .addStatement(
                            "return entries.firstOrNull { it.xml == value } ?: throw IllegalArgumentException(%P)",
                            "Unknown ${def.origin} value: \$value",
                        ).build(),
                ).build(),
        )
        return FileSpec.builder(name).addType(builder.build()).build()
    }

    private fun choiceFile(def: ChoiceDef): FileSpec {
        val name = ClassName(pkg, def.name)
        val alternatives = def.variants.joinToString(", ") { "`${it.cls.name}`" }
        val type =
            TypeSpec
                .interfaceBuilder(name)
                .addModifiers(KModifier.SEALED)
                .addAnnotation(optics)
                .addKdoc("openTRANS 2.1: xsd:choice `${def.origin}`, one of $alternatives. Generated, do not edit.")
                .addType(TypeSpec.companionObjectBuilder().build())
                .build()
        return FileSpec.builder(name).addType(type).build()
    }

    /** Common supertype of the ten document types, so a reader can return "any openTRANS document". */
    private fun documentFile(ir: Ir): FileSpec {
        val names = ir.roots.joinToString(", ") { "`${it.name}`" }
        val type =
            TypeSpec
                .interfaceBuilder(document)
                .addModifiers(KModifier.SEALED)
                .addAnnotation(optics)
                .addKdoc("Any openTRANS 2.1 document: one of $names. Generated, do not edit.")
                .addType(TypeSpec.companionObjectBuilder().build())
                .build()
        return FileSpec.builder(document).addType(type).build()
    }

    private fun xmlLabel(prop: Prop): String =
        when (val b = prop.binding) {
            is Binding.Element -> b.name.localPart
            is Binding.Attribute -> "@${b.name}"
            else -> prop.name
        }
}
