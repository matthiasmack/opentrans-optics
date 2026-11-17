// Kotlin DSL for creating openTRANS documents.
// The builders for every class are generated from the XSD by :codegen;
// hand-written on top: order(id, date) { } with derived values.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

val codegen: Configuration = configurations.create("codegen")

dependencies {
    api(project(":opentrans-model"))
    codegen(project(":codegen"))
    testImplementation(project(":opentrans-xml"))
    testImplementation(testFixtures(project(":opentrans-model")))
}

val schemaDir = rootProject.layout.projectDirectory.dir("opentrans-xml/src/main/resources/opentrans/xsd/2.1")
val generatedDir = layout.buildDirectory.dir("generated/opentrans/kotlin")

val generateDsl = tasks.register<JavaExec>("generateDsl") {
    description = "Generates the openTRANS builder DSL from the XSD."
    classpath = codegen
    mainClass = "opentrans.codegen.GenerateKt"
    inputs.dir(schemaDir)
    outputs.dir(generatedDir)
    args(
        "dsl",
        schemaDir.file("opentrans_2_1.xsd").asFile.absolutePath,
        generatedDir.get().asFile.absolutePath,
        "opentrans.dsl",
        "opentrans.model",
    )
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateDsl)
}
