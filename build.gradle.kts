plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "opentrans"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
        dependencies {
            "testImplementation"(kotlin("test"))
            "testImplementation"("org.junit.jupiter:junit-jupiter-params")
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
