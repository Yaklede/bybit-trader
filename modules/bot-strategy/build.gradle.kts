dependencies {
    implementation(project(":modules:bot-domain"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}
