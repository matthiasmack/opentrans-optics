plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "io.github.matthiasmack"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        // Formatting: `./gradlew ktlintFormat` fixes, `./gradlew build` checks. Rules in .editorconfig.
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set(libs.versions.ktlint.cli.get())
            filter {
                // Generated code (KotlinPoet, Arrow KSP) is never edited by hand.
                exclude { it.file.path.contains("/build/") }
            }
        }
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
