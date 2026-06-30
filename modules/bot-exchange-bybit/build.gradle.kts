plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":modules:bot-domain"))
    implementation(project(":modules:bot-engine"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockk)
}
