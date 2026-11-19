// Immutable data classes for openTRANS 2.1 documents.
// Most of the model is generated from the XSD by :codegen at build time;
// Arrow then generates the optics for every @optics class via KSP.
// Reading and writing documents live in opentrans-optics-xml; the model only has the
// XmlContent tree for free XML (UDX) and the UdxType API (package io.github.matthiasmack.opentrans.udx).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    `java-test-fixtures`
}

val codegen: Configuration = configurations.create("codegen")

dependencies {
    api(libs.arrow.optics)
    ksp(libs.arrow.optics.ksp)
    codegen(project(":codegen"))
}

val schemaDir = rootProject.layout.projectDirectory.dir("opentrans-optics-xml/src/main/resources/io/github/matthiasmack/opentrans/xsd/2.1")
val generatedDir = layout.buildDirectory.dir("generated/opentrans/kotlin")

val generateModel = tasks.register<JavaExec>("generateModel") {
    description = "Generates the openTRANS model classes from the XSD."
    classpath = codegen
    mainClass = "io.github.matthiasmack.opentrans.codegen.GenerateKt"
    inputs.dir(schemaDir)
    outputs.dir(generatedDir)
    args(
        "model",
        schemaDir.file("opentrans_2_1.xsd").asFile.absolutePath,
        generatedDir.get().asFile.absolutePath,
        "io.github.matthiasmack.opentrans.model",
    )
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateModel)
}

// KSP registers its task late, so match it lazily.
tasks.matching { it.name == "kspKotlin" }.configureEach {
    dependsOn(generateModel)
}
