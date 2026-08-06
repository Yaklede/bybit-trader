plugins {
    application
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass.set("dev.yaklede.bybittrader.app.ApplicationKt")
}

tasks.register<JavaExec>("runMultiHorizonParity") {
    group = "verification"
    description = "Replays the frozen multi-horizon research profile and writes a trade trace."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.yaklede.bybittrader.app.research.MultiHorizonParityMainKt")
}

tasks.register<JavaExec>("runMakerShadowReplay") {
    group = "verification"
    description = "Replays sealed raw market events through the conservative maker shadow engine."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.yaklede.bybittrader.app.research.MakerShadowReplayMainKt")
}

tasks.register<JavaExec>("runMakerShadowReplayMatrix") {
    group = "verification"
    description = "Runs the frozen maker shadow queue and cost stress matrix."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.yaklede.bybittrader.app.research.MakerShadowReplayMatrixMainKt")
}

tasks.register<JavaExec>("runVolumeConfirmedTrendParity") {
    group = "verification"
    description = "Replays the frozen volume-confirmed trend protocol through the Kotlin core."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.yaklede.bybittrader.app.research.VolumeConfirmedTrendParityMainKt")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("generateVolumeConfirmedTrendBootstrap") {
    group = "verification"
    description = "Builds the deterministic trend indicator bootstrap from frozen Bybit evidence."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.yaklede.bybittrader.app.research.VolumeConfirmedTrendBootstrapMainKt")
    workingDir = rootProject.projectDir
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
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cio)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}
