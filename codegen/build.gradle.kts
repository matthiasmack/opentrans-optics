// Build-time generator: reads the openTRANS XSD and emits Kotlin sources.
// Not published; its dependencies never reach library users.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(libs.xerces)
    implementation(libs.kotlinpoet)
}

val schemaDir = rootProject.layout.projectDirectory.dir("opentrans-optics-xml/src/main/resources/io/github/matthiasmack/opentrans/xsd/itscope")

tasks.register<JavaExec>("inventory") {
    description = "Prints which XSD constructs are reachable from the openTRANS document roots."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.matthiasmack.opentrans.codegen.InventoryKt"
    args(schemaDir.file("opentrans_2_1.xsd").asFile.absolutePath)
}

tasks.register<JavaExec>("collisions") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.matthiasmack.opentrans.codegen.CollisionsKt"
    args(schemaDir.file("opentrans_2_1.xsd").asFile.absolutePath)
}

tasks.register<JavaExec>("reach") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.matthiasmack.opentrans.codegen.ReachKt"
    args(schemaDir.file("opentrans_2_1.xsd").asFile.absolutePath)
}

tasks.register<JavaExec>("generatePreview") {
    description = "Generates the model into build/preview for inspection."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.matthiasmack.opentrans.codegen.GenerateKt"
    args("model", schemaDir.file("opentrans_2_1.xsd").asFile.absolutePath, layout.buildDirectory.dir("preview").get().asFile.absolutePath, "opentrans.generated")
}
