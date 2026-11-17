// Reading, writing and XSD validation of openTRANS 2.1 XML.
// Reader and writer are generated from the XSD by :codegen at build time.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

val codegen: Configuration = configurations.create("codegen")

dependencies {
    api(project(":opentrans-model"))
    codegen(project(":codegen"))
    testImplementation(testFixtures(project(":opentrans-model")))
}

val schemaDir = layout.projectDirectory.dir("src/main/resources/opentrans/xsd/2.1")
val generatedDir = layout.buildDirectory.dir("generated/opentrans/kotlin")

val generateXml = tasks.register<JavaExec>("generateXml") {
    description = "Generates the openTRANS XML reader and writer from the XSD."
    classpath = codegen
    mainClass = "opentrans.codegen.GenerateKt"
    inputs.dir(schemaDir)
    outputs.dir(generatedDir)
    args(
        "xml",
        schemaDir.file("opentrans_2_1.xsd").asFile.absolutePath,
        generatedDir.get().asFile.absolutePath,
        "opentrans.xml",
        "opentrans.model",
    )
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateXml)
}
