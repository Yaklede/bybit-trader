plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.sqldelight) apply false
}

allprojects {
    group = "dev.yaklede.bybittrader"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension>("ktlint") {
        filter {
            exclude("**/build/generated/**")
            exclude { element -> element.file.path.contains("/build/generated/") }
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.register("lint") {
    group = "verification"
    description = "Runs Kotlin style checks for all modules."
    dependsOn(subprojects.map { "${it.path}:ktlintCheck" })
}

tasks.register("format") {
    group = "formatting"
    description = "Formats Kotlin sources for all modules."
    dependsOn(subprojects.map { "${it.path}:ktlintFormat" })
}
