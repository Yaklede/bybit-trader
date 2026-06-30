plugins {
    application
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("dev.yaklede.bybittrader.app.ApplicationKt")
}

dependencies {
    implementation(project(":modules:bot-api"))
    implementation(project(":modules:bot-alerts"))
    implementation(project(":modules:bot-domain"))
    implementation(project(":modules:bot-engine"))
    implementation(project(":modules:bot-exchange-bybit"))
    implementation(project(":modules:bot-ledger"))
    implementation(project(":modules:bot-strategy"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cio)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.logback.classic)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}
