dependencies {
    implementation(project(":modules:bot-domain"))
    implementation(project(":modules:bot-strategy"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}
