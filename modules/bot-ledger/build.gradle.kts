plugins {
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("dev.yaklede.bybittrader.ledger.db")
        }
    }
}

dependencies {
    implementation(project(":modules:bot-alerts"))
    implementation(project(":modules:bot-domain"))
    implementation(project(":modules:bot-engine"))
    implementation(libs.sqldelight.sqlite.driver)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.sqlite.jdbc)
}
